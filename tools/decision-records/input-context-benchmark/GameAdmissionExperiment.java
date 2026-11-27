package forge.ai;

import com.google.gson.GsonBuilder;
import forge.game.Game;
import forge.game.event.GameEventTurnPhase;
import forge.game.player.Player;
import forge.util.MyRandom;
import forge.view.Main;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Isolated development classpath only; never used by ordinary production capture. */
public final class GameAdmissionExperiment {
    private static final String POLICY = System.getProperty("forge.admission.policy", "PRODUCTION");
    private static final long SALT = Long.getLong("forge.admission.salt", 0);
    private static final int MAX_GROUPS = 8192;
    private static final List<Actor> ACTORS = new ArrayList<>();
    private static final ThreadLocal<ArrayDeque<Ticket>> ACTIVE = ThreadLocal.withInitial(ArrayDeque::new);
    private static Game selectedGame;
    private static List<String> commandArguments=List.of();
    private static final Field REMAINING;
    static {
        try { REMAINING=AiInputContext.State.class.getDeclaredField("remaining");REMAINING.setAccessible(true); }
        catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
    private GameAdmissionExperiment() { }
    private static void check(boolean condition,String message) { if(!condition){throw new IllegalStateException(message);} }
    private static long mix(long z) { z=(z^(z>>>30))*0xbf58476d1ce4e5b9L;z=(z^(z>>>27))*0x94d049bb133111ebL;return z^(z>>>31); }
    private static int turn(Player p) { return p.getGame().getPhaseHandler().getTurn(); }
    private static boolean enabled(Player p) { return p.getGame().isRecordingAiDecisions()&&!p.getGame().isSimulationCopy(); }
    public static long initialAllowance(Player p,long share) {
        return enabled(p)&&POLICY.equals("RESERVE")&&turn(p)<31?share/2:share;
    }
    public static Actor register(Player p,AiInputContext.State state,long share) {
        if(!enabled(p)){return null;}
        final Actor actor;
        synchronized(ACTORS){
            check(selectedGame==null||selectedGame==p.getGame(),"Prototype supports one original game per JVM");selectedGame=p.getGame();
            check(ACTORS.size()<16&&ACTORS.stream().noneMatch(a->a.player==p),"Duplicate/overflow actor registration");
            actor=new Actor(p,state,share);ACTORS.add(actor);
        }
        p.getGame().observeEventEmission(event->{if(event instanceof GameEventTurnPhase){synchronized(actor){actor.releaseIfDue();}}});
        return actor;
    }
    static final class Group {
        long records,work,available,partial,cards,facts,linkedFacts;
        final Map<String,Long> reasons=new LinkedHashMap<>(),zones=new LinkedHashMap<>();
        Map<String,Object> snapshot(){
            Map<String,Object> d=new LinkedHashMap<>();d.put("records",records);d.put("work",work);d.put("available",available);d.put("partial",partial);d.put("representedCards",cards);d.put("disclosedFacts",facts);d.put("linkedFacts",linkedFacts);d.put("omissionReasons",new LinkedHashMap<>(reasons));d.put("representedZones",new LinkedHashMap<>(zones));return d;
        }
    }
    public static final class Actor {
        final Player player;final AiInputContext.State state;final long share;final AtomicLong remaining;
        final Map<String,Group> groups=new LinkedHashMap<>();
        long funded,held,entered,returned,threw,captureWork,outsideWork,returnedWork,failedWork;
        int inFlight,maximumInFlight,releaseTurn=-1;boolean sealed,overflow;
        Actor(Player p,AiInputContext.State s,long share){
            player=p;state=s;this.share=share;
            try { remaining=(AtomicLong)REMAINING.get(s); }catch(IllegalAccessException error){throw new IllegalStateException(error);}
            funded=remaining.get();held=share-funded;check(held==0||POLICY.equals("RESERVE")&&held==share-share/2,"Unexpected initial funding");
        }
        void releaseIfDue(){
            if(!sealed&&held>0&&turn(player)>=31){remaining.addAndGet(held);funded+=held;held=0;releaseTurn=turn(player);}
        }
        Map<String,Object> seal(){
            sealed=true;
            Map<String,Object> d=new LinkedHashMap<>();d.put("playerId",player.getId());d.put("share",share);d.put("funded",funded);d.put("held",held);d.put("remaining",remaining.get());d.put("releaseTurn",releaseTurn);d.put("finalGameTurn",turn(player));
            d.put("entered",entered);d.put("returned",returned);d.put("threw",threw);d.put("inFlight",inFlight);d.put("maximumInFlight",maximumInFlight);d.put("captureWork",captureWork);d.put("outsideCaptureWork",outsideWork);d.put("returnedWork",returnedWork);d.put("failedWork",failedWork);d.put("inFlightWork",captureWork-returnedWork-failedWork);d.put("overflow",overflow);
            Map<String,Object> saved=new LinkedHashMap<>();for(var e:groups.entrySet()){saved.put(e.getKey(),e.getValue().snapshot());}d.put("groups",saved);return d;
        }
    }
    public static final class Ticket {
        final Actor actor;final long ordinal;final int turn;final String timing,basis,reason;final boolean counted;
        long work;
        Ticket(Actor a,long ordinal,int turn,String timing,String basis,String reason,boolean counted){actor=a;this.ordinal=ordinal;this.turn=turn;this.timing=timing;this.basis=basis;this.reason=reason;this.counted=counted;}
    }
    public static Ticket begin(Player p,AiInputContext.State state,Actor actor,String timing,String basis){
        check(actor!=null&&actor.player==p&&actor.state==state,"Unsupported preallocated capture State");
        synchronized(actor){
            if(actor.sealed){return new Ticket(actor,0,turn(p),timing,basis,"INTERRUPTED",false);}
            actor.releaseIfDue();check(actor.entered<Long.MAX_VALUE,"Capture ordinal overflow");
            long ordinal=++actor.entered;int denominator=POLICY.equals("HASH4")?4:POLICY.equals("HASH8")?8:1;
            String reason=denominator>1&&(mix(ordinal^mix(SALT+p.getId()))&(denominator-1))!=0?"EXPERIMENT_SAMPLED":null;
            if(reason==null&&POLICY.equals("RESERVE")&&actor.held>0&&actor.remaining.get()==0){reason="EXPERIMENT_RESERVED";}
            Ticket ticket=new Ticket(actor,ordinal,turn(p),timing,basis,reason,true);ArrayDeque<Ticket> stack=ACTIVE.get();check(stack.size()<64,"Capture nesting overflow");stack.push(ticket);
            actor.inFlight++;actor.maximumInFlight=Math.max(actor.maximumInFlight,actor.inFlight);return ticket;
        }
    }
    /** Called once after a successful native CAS, while holding this development actor's monitor. */
    public static void debit(Actor actor){
        if(actor==null||actor.sealed){return;}
        Ticket owner=null;for(Ticket t:ACTIVE.get()){if(t.actor==actor&&t.counted){owner=t;break;}}
        if(owner==null){actor.outsideWork++;}else{owner.work++;actor.captureWork++;}
    }
    private static void pop(Ticket t){check(ACTIVE.get().pop()==t,"Corrupt capture ticket stack");}
    public static Map<String,Object> finish(Ticket t,Map<String,Object> context){
        if(!t.counted){return context;}
        synchronized(t.actor){
            pop(t);if(t.actor.sealed){return context;}
            Actor a=t.actor;a.returned++;a.inFlight--;a.returnedWork+=t.work;
            String category=t.reason!=null?t.reason:context.get("policy")!=null?"AVAILABLE":((List<?>)context.get("omissions")).stream().anyMatch(o->((Map<?,?>)o).get("reason").equals("BUDGET_EXHAUSTED"))?"BUDGET":"OTHER_UNAVAILABLE";
            String key=t.turn+":"+t.timing+":"+t.basis+":"+category;
            if(!a.groups.containsKey(key)&&a.groups.size()==MAX_GROUPS){a.overflow=true;key="OVERFLOW";}
            Group g=a.groups.computeIfAbsent(key,k->new Group());g.records++;g.work+=t.work;
            Set<String> reasons=new java.util.HashSet<>();for(Object o:(List<?>)context.get("omissions")){reasons.add((String)((Map<?,?>)o).get("reason"));}for(String reason:reasons){g.reasons.merge(reason,1L,Long::sum);}
            if(context.get("policy") instanceof Map<?,?> view){
                g.available++;if(context.get("status").equals("PARTIAL")){g.partial++;}
                for(Object o:(List<?>)view.get("cards")){Map<?,?> card=(Map<?,?>)o;g.cards++;g.zones.merge((String)card.get("zone"),1L,Long::sum);}
                for(Object o:(List<?>)view.get("disclosedFacts")){Map<?,?> fact=(Map<?,?>)o;g.facts++;Object link=fact.get("currentObjectAssociation");if(link!=null&&!link.equals("UNRESOLVED")){g.linkedFacts++;}}
            }
        }
        return context;
    }
    public static void failed(Ticket t){
        if(!t.counted){return;}synchronized(t.actor){pop(t);if(t.actor.sealed){return;}t.actor.threw++;t.actor.inFlight--;t.actor.failedWork+=t.work;}
    }
    private static void writeReport(Path path){
        try{
            Map<String,Object> d=new LinkedHashMap<>();d.put("commandArguments",commandArguments);d.put("schemaVersion",1);d.put("scope","DEVELOPMENT_CAPTURE_ATTEMPTS_AT_PROCESS_SHUTDOWN");d.put("policy",POLICY);d.put("salt",SALT);d.put("releaseThreshold",31);d.put("maximumGroups",MAX_GROUPS);d.put("trainingReady",false);
            List<Object> nodes=new ArrayList<>();synchronized(ACTORS){for(Actor a:ACTORS){synchronized(a){nodes.add(a.seal());}}}d.put("actors",nodes);
            long countBefore=MyRandom.callCount();ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream stream=new ObjectOutputStream(bytes)){stream.writeObject(MyRandom.getRandom());}
            d.put("rngCalls",countBefore);d.put("rngCallsAfterSnapshot",MyRandom.callCount());d.put("rngStateSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())));
            Files.writeString(path,new GsonBuilder().serializeNulls().create().toJson(d)+"\n");
        }catch(Throwable failure){System.err.println("Admission sidecar failed");failure.printStackTrace(System.err);}
    }
    public static void main(String[] args){
        check(Set.of("PRODUCTION","PREFIX","HASH4","HASH8","RESERVE").contains(POLICY)&&SALT>=0,"Invalid experimental policy");
        commandArguments=List.copyOf(java.util.Arrays.asList(args));
        Path path=Path.of(System.getProperty("forge.admission.sidecar"));Runtime.getRuntime().addShutdownHook(new Thread(()->writeReport(path),"admission-cutoff"));Main.main(args);
    }
}
