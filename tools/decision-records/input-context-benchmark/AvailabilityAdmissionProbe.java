package forge.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.management.ThreadMXBean;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import forge.view.TraceWriterProbeSink;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Development JVM only. The generated overlay calls the two public hooks; production does not. */
public final class AvailabilityAdmissionProbe extends AITest {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final ThreadMXBean CLOCK = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final boolean OVERLAY = Boolean.getBoolean("forge.availability.overlay");
    private static Trial current;
    private static long cpu() { return CLOCK.getCurrentThreadCpuTime(); }
    private static long allocated() { return CLOCK.getThreadAllocatedBytes(Thread.currentThread().getId()); }
    private record Policy(String name, int denominator, long salt) { }
    private static final List<Policy> POLICIES = List.of(new Policy("PREFIX", 1, 0),
            new Policy("STRIDE", 4, 0), new Policy("STRIDE", 4, 1), new Policy("STRIDE", 4, 2), new Policy("STRIDE", 4, 3),
            new Policy("HASH", 4, 0), new Policy("HASH", 4, 1), new Policy("HASH", 8, 0), new Policy("HASH", 8, 1),
            new Policy("RESERVE", 1, 0));
    private record Workload(String name, int players, int board, boolean disclosures, boolean grows) { }
    private static final List<Workload> WORKLOADS = List.of(new Workload("SMALL2", 2, 4, false, false),
            new Workload("PUBLIC4", 4, 18, true, false), new Workload("GROWING4", 4, 18, true, true));
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
    private static void check(boolean value, String message) { if (!value) { throw new IllegalStateException(message); } }

    public static long initialAllowance(Player chooser, long share) {
        Trial t = current;
        return t != null && chooser == t.chooser && t.policy.name.equals("RESERVE") ? share / 2 : share;
    }
    public static String beforeCapture(Player chooser, AiInputContext.State state) {
        Trial t = current;
        check(t != null && chooser == t.chooser && state == t.state, "Unexpected overlay capture");
        t.gateCalls++;
        Frame frame = t.frames.peek(); check(frame != null, "Unframed input capture");
        frame.ordinal = ++t.ordinal;
        boolean admitted = switch (t.policy.name) {
            case "STRIDE" -> (frame.ordinal - 1) % t.policy.denominator == t.policy.salt;
            case "HASH" -> (mix(frame.ordinal ^ mix(t.policy.salt + chooser.getId())) & (t.policy.denominator - 1)) == 0;
            default -> true;
        };
        if (!admitted) { frame.reason = "EXPERIMENT_SAMPLED"; }
        else if (t.policy.name.equals("RESERVE") && !t.released && state.remainingWork() == 0) {
            frame.reason = "EXPERIMENT_RESERVED";
        }
        return frame.reason;
    }
    private static final class Frame {
        final String family; final long beforeWork, beforeCpu, beforeAllocated;
        long ordinal; Object id; String reason; boolean started, finished;
        Frame(String family, Trial t) { this.family = family; beforeWork = t.state.remainingWork(); beforeCpu = cpu(); beforeAllocated = allocated(); }
    }
    private static final class Stats {
        long records, work, entryCpu, entryAllocated, partial, cards, knownCards, facts, linkedFacts, rulesProfiles, rulesFacts;
        final Map<String, Long> zones = new LinkedHashMap<>(), reasons = new LinkedHashMap<>();
        Map<String, Object> data() { Map<String,Object> result = new LinkedHashMap<>(); result.put("records",records);result.put("work",work);result.put("entryCpuNanos",entryCpu);result.put("entryAllocatedBytes",entryAllocated);result.put("partialProfiles",partial);result.put("rulesStateProfiles",rulesProfiles);result.put("rulesStateFacts",rulesFacts);result.put("representedCards",cards);result.put("identityKnownCards",knownCards);result.put("disclosedFacts",facts);result.put("linkedFacts",linkedFacts);result.put("representedZones",zones);result.put("omissionReasons",reasons);return result; }
    }
    private static final class Trial {
        final Policy policy; final Workload workload; final int cycles, round;
        final ArrayDeque<Frame> frames = new ArrayDeque<>();
        final Map<String, Stats> groups = new LinkedHashMap<>();
        final Map<String, Object> policySamples = new LinkedHashMap<>();
        Game game; Player chooser; AiInputContext.State state; SpellAbility ability; CardCollection offered;
        AiContextDisclosures.Prompt prompt; AiObservationAccounting ledger; TraceWriterProbeSink sink;
        long share, ordinal, gateCalls, callbacks, checksum, starts, terminals, captureWork, disclosureWork, funded;
        int turn = 1; boolean released;
        Trial(Policy p, Workload w, int cycles, int round) { policy=p; workload=w; this.cycles=cycles; this.round=round; }
        void releaseIfDue() throws ReflectiveOperationException {
            if (OVERLAY && policy.name.equals("RESERVE") && !released && turn >= 31) {
                Field field = AiInputContext.State.class.getDeclaredField("remaining"); field.setAccessible(true);
                long held = share - funded;
                ((AtomicLong) field.get(state)).addAndGet(held); funded += held; released = true;
            }
        }
        void delivered() throws ReflectiveOperationException {
            if (!workload.disclosures) { return; }
            releaseIfDue(); long before = state.remainingWork();
            if (prompt != null) { prompt.close(); }
            prompt = state.disclosures().prompt();
            CardCollection shown = new CardCollection();
            for (Card c : offered) { if (shown.size() == 32) { break; } shown.add(c); }
            state.disclosures().cards(shown, ZoneType.Battlefield);
            disclosureWork += before - state.remainingWork();
        }
        <T> T choice(String family, Supplier<T> callback) {
            try { releaseIfDue(); } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            Frame f = new Frame(family, this); frames.push(f);
            try { T result = callback.get(); if(game.getEventObservationFailure()!=null){throw new IllegalStateException("Observer failed",game.getEventObservationFailure());} check(f.started && f.finished, "Missing lifecycle"); return result; }
            finally { check(frames.pop() == f, "Corrupt frame stack"); }
        }
        void returned(int value) { callbacks++; checksum += value; }
        void event(AiChoiceEvent event) {
            Map<String, Object> c = event.data(); Frame f = frames.peek(); check(f != null, "Unframed event");
            if (c.get("status").equals("STARTED")) {
                check(!f.started, "Duplicate start"); f.started=true; f.id=c.get("choiceId"); starts++;
                if (!OVERLAY) { f.ordinal=++ordinal; }
                if (f.family.equals("DRAW")) {
                    Frame parent = frames.stream().skip(1).findFirst().orElseThrow();
                    check(parent.family.equals("DAMAGE") && parent.id.equals(c.get("parentChoiceId")), "Lost native nested link");
                } else { check(c.get("parentChoiceId") == null, "Invented parent"); }
                Map<?, ?> context = (Map<?, ?>) c.get("inputContext"); check(context != null, "Missing input");
                boolean available = context.get("policy") != null;
                String category = f.reason != null ? f.reason : available ? "AVAILABLE" : policy.name.equals("RESERVE") && !released ? "RESERVE_ATTEMPT_FAILED" : "BUDGET";
                if (f.reason != null) {
                    check(context.get("status").equals("UNAVAILABLE") && ((Map<?, ?>)((List<?>)context.get("omissions")).get(0)).get("reason").equals(f.reason), "Lost omission reason");
                } else if (!available) {
                    check(((List<?>) context.get("omissions")).stream().anyMatch(o -> ((Map<?, ?>) o).get("reason").equals("BUDGET_EXHAUSTED")), "Unexpected unavailable input");
                }
                long work = f.beforeWork - state.remainingWork(); check(work >= 0 && (f.reason == null || work == 0), "Unaccounted debit"); captureWork += work;
                sink.write(c);
                long entryCpu=cpu()-f.beforeCpu, entryAllocated=allocated()-f.beforeAllocated;
                String key = ((turn-1)/10) + ":" + f.family + ":" + category;
                Stats s = groups.computeIfAbsent(key, ignored -> new Stats()); s.records++; s.work += work; s.entryCpu += entryCpu; s.entryAllocated += entryAllocated;
                java.util.Set<String> reasons = new java.util.HashSet<>();
                for(Object item:(List<?>)context.get("omissions")){reasons.add((String)((Map<?,?>)item).get("reason"));}
                for(String reason:reasons){s.reasons.merge(reason,1L,Long::sum);}
                if(available){
                    Map<?,?> view=(Map<?,?>)context.get("policy");s.partial+=context.get("status").equals("PARTIAL")?1:0;s.rulesProfiles+=context.get("stateBasis").equals("RULES_STATE")?1:0;long viewLinks=0;
                    for(Object item:(List<?>)view.get("cards")){Map<?,?> card=(Map<?,?>)item;s.cards++;if(Boolean.TRUE.equals(card.get("identityKnown"))){s.knownCards++;}s.zones.merge((String)card.get("zone"),1L,Long::sum);}
                    for(Object item:(List<?>)view.get("disclosedFacts")){Map<?,?> fact=(Map<?,?>)item;s.facts++;if(context.get("stateBasis").equals("RULES_STATE")){s.rulesFacts++;}if(fact.get("currentObjectAssociation")!=null&&!fact.get("currentObjectAssociation").equals("UNRESOLVED")){s.linkedFacts++;viewLinks++;}}
                    if(workload.disclosures&&(!workload.grows||turn<31)&&context.get("stateBasis").equals("RULES_STATE")){check(viewLinks==((List<?>)view.get("disclosedFacts")).size(),"Public-proof fixture lost its bindings");}
                }
                if (available) {
                    String sampleKey = f.family + ":" + (workload.grows && turn >= 31 ? "GROWN" : "INITIAL") + ":" + context.get("stateBasis");
                    policySamples.putIfAbsent(sampleKey, context.get("policy"));
                }
            } else {
                check(f.started && !f.finished && f.id.equals(c.get("choiceId")) && c.get("status").equals("COMPLETED"), "Bad terminal");
                f.finished=true; terminals++; sink.write(c);
            }
        }
    }

    private Game fixture(Workload w) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i=0;i<w.players;i++) { players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("p"+i,null))); }
        GameRules rules = new GameRules(GameType.Commander);
        Game game = new Game(players,rules,new Match(rules,players,"admission prototype")); game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1,game.getPlayers().get(1)); game.getPhaseHandler().onStackResolved();
        for (int p=0;p<w.players;p++) {
            Player player=game.getPlayers().get(p); fillLibrary(player,32);
            for(int i=0;i<7;i++) { addCardToZone("Island",player,ZoneType.Hand); }
            for(int i=0;i<w.board;i++) { Card card=addCardToZone("Grizzly Bears",player,ZoneType.Battlefield); card.setName("Public "+p+" "+i); }
        }
        return game;
    }
    private Map<String,Object> trial(Workload w,Policy policy,int cycles,int round) throws Exception {
        Trial t=new Trial(policy,w,cycles,round); current=t;
        Random saved=MyRandom.getRandom();
        try {
            t.game=fixture(w);t.chooser=t.game.getPlayers().get(1);t.share=AiInputContext.MAX_GAME_WORK/w.players;
            t.ability=addCardToZone("Lightning Bolt",t.chooser,ZoneType.Hand).getFirstSpellAbility();t.ability.setActivatingPlayer(t.chooser);
            t.offered=new CardCollection();for(Player p:t.game.getPlayers()){t.offered.addAll(p.getCardsIn(ZoneType.Battlefield));}
            t.game.setRecordAiDecisions(true);PlayerControllerAi pc=(PlayerControllerAi)t.chooser.getController();t.state=pc.inputContextState();t.funded=t.state.remainingWork();
            check(t.funded==(OVERLAY&&policy.name.equals("RESERVE")?t.share/2:t.share),"Wrong initial funding");
            t.ledger=pc.configureObservationAccounting();t.sink=new TraceWriterProbeSink();t.game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){t.event(c);}});t.delivered();check(t.state.disclosures().snapshot().facts().size()==(w.disclosures?32:0),"Initial disclosure fixture incomplete");
            MyRandom.setRandom(new Random(49177));long beforeCpu=cpu(),beforeAllocated=allocated(),excludedCpu=0,excludedAllocated=0;
            for(int cycle=0;cycle<cycles;cycle++) {
                t.turn=1+cycle/10; // explicit fixture clock; do not fire a native event and invalidate proofs
                if(w.grows&&cycle==300) {
                    long growthCpu=cpu(),growthAllocated=allocated();
                    for(int p=0;p<w.players;p++){for(int i=0;i<22;i++){Card card=addCardToZone("Grizzly Bears",t.game.getPlayers().get(p),ZoneType.Battlefield);card.setName("Growth "+p+" "+i);t.offered.add(card);}}
                    excludedCpu+=cpu()-growthCpu;excludedAllocated+=allocated()-growthAllocated;
                }
                if(cycle>0&&cycle%40==0){t.delivered();}
                Runnable operation=()->{
                    AiAbilityDecision expected=new AiAbilityDecision(100,AiPlayDecision.WillPlay);
                    check(t.choice("DAMAGE",()->AiDamageChoiceRecorder.canPlay(t.chooser,t.ability,()->{
                        t.returned(11);check(t.choice("DRAW",()->AiDrawChoiceRecorder.policy(t.chooser,t.ability,false,()->{t.returned(13);return true;})),"Changed Draw return");return expected;
                    }))==expected,"Changed Damage identity");
                    check(t.choice("NUMBER",()->AiNumberChoiceRecorder.range(t.chooser,t.ability,"CONTROLLER_NUMBER_RANGE",0,7,()->{t.returned(3);return 3;}))==3,"Changed number");
                    Card selected=t.choice("ENTITY",()->AiEffectChoiceRecorder.single(t.chooser,t.ability,t.offered,false,null,()->{t.returned(17);return t.offered.get(0);}));check(selected==t.offered.get(0),"Changed entity identity");
                };
                if(cycle%2==0){AiChoiceRecorder.planning(t.chooser,()->{operation.run();return true;});}else{operation.run();}
            }
            t.sink.close();long totalCpu=cpu()-beforeCpu-excludedCpu,totalAllocated=allocated()-beforeAllocated-excludedAllocated;
            check(MyRandom.getRandom().nextLong()==new Random(49177).nextLong(),"Engine RNG changed");
            check(t.game.getEventObservationFailure()==null,"Observer failed: "+t.game.getEventObservationFailure());
            check(t.callbacks==4L*cycles&&t.checksum==44L*cycles&&t.starts==t.callbacks&&t.terminals==t.callbacks&&t.ordinal==t.callbacks,"Lost native callback");
            check(t.gateCalls==(OVERLAY?t.callbacks:0),"Overlay hook count");
            check(t.captureWork+t.disclosureWork+t.state.remainingWork()==t.funded&&t.funded<=t.share,"Debit conservation");
            Map<String,Object> samples=new LinkedHashMap<>();for(var e:t.policySamples.entrySet()){samples.put(e.getKey(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.toJson(e.getValue()).getBytes(StandardCharsets.UTF_8))));}
            Map<String,Object> row=new LinkedHashMap<>();row.put("profile",AiInputContext.PROFILE);row.put("overlay",OVERLAY);row.put("workload",w.name);row.put("players",w.players);row.put("initialBoardPerPlayer",w.board);row.put("growsAtTurn31",w.grows);row.put("disclosures",w.disclosures);row.put("cycles",cycles);row.put("round",round);row.put("policy",policy.name);row.put("denominator",policy.denominator);row.put("salt",policy.salt);
            row.put("share",t.share);row.put("funded",t.funded);row.put("held",t.share-t.funded);row.put("remaining",t.state.remainingWork());row.put("captureWork",t.captureWork);row.put("disclosureWork",t.disclosureWork);row.put("callbacks",t.callbacks);row.put("checksum",t.checksum);row.put("starts",t.starts);row.put("terminals",t.terminals);row.put("gateCalls",t.gateCalls);row.put("cpuNanos",totalCpu);row.put("allocatedBytes",totalAllocated);row.put("writerCharacters",t.sink.characters());row.put("policyHashes",samples);
            Map<String,Object> groups=new LinkedHashMap<>();for(var e:t.groups.entrySet()){groups.put(e.getKey(),e.getValue().data());}row.put("groups",groups);row.put("accounting",t.ledger.snapshot());
            List<Long> inactive=new ArrayList<>();for(Player p:t.game.getPlayers()){if(p!=t.chooser){long allowance=((PlayerControllerAi)p.getController()).inputContextState().remainingWork();check(allowance==t.share,"Borrowed another player's share");inactive.add(allowance);}}row.put("inactiveRemaining",inactive);return row;
        } finally { if(t.prompt!=null){t.prompt.close();}MyRandom.setRandom(saved);current=null; }
    }
    public static void main(String[] args) {
        try {
            check(CLOCK.isCurrentThreadCpuTimeSupported()&&CLOCK.isThreadAllocatedMemorySupported(),"Missing thread metrics");CLOCK.setThreadCpuTimeEnabled(true);CLOCK.setThreadAllocatedMemoryEnabled(true);
            AvailabilityAdmissionProbe probe=new AvailabilityAdmissionProbe();probe.initializeModel();List<Map<String,Object>> rows=new ArrayList<>();
            if(Boolean.getBoolean("forge.availability.smoke")){rows.add(probe.trial(WORKLOADS.get(1),OVERLAY?POLICIES.get(9):POLICIES.get(0),600,0));Files.writeString(Path.of(args[0]),JSON.toJson(rows)+"\n");System.exit(0);}
            boolean reverse=Boolean.getBoolean("forge.availability.reverse");int rounds=Integer.getInteger("forge.availability.rounds",3);
            for(Workload w:WORKLOADS){for(int cycles:new int[]{60,600}){for(int round=-1;round<rounds;round++){
                int count=OVERLAY?POLICIES.size():1;for(int offset=0;offset<count;offset++){int i=Math.floorMod(round+offset,count);if(reverse){i=count-1-i;}Map<String,Object> row=probe.trial(w,POLICIES.get(i),cycles,round);if(round>=0){rows.add(row);}}
                Files.writeString(Path.of(args[0]),JSON.toJson(rows)+"\n");System.out.println(w.name+" cycles="+cycles+" round="+round);System.out.flush();
            }}}
            System.exit(0);
        }catch(Throwable failure){failure.printStackTrace(System.err);System.exit(1);}
    }
}
