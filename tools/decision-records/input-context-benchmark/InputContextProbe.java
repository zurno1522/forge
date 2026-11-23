package forge.ai;

import com.google.gson.Gson;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/** Development-only cost probe: capture + detached event freezing + Gson, no selector work. */
public final class InputContextProbe extends AITest {
    private static volatile Object consumed;
    private static final Gson GSON = new Gson();
    private final com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static long reachableBytes(Object root) throws Exception {
        if (root == null) { return 0; }
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object> queue = new ArrayDeque<>(); queue.add(root); long bytes = 0;
        while (!queue.isEmpty()) {
            Object value = queue.removeFirst();
            if (!seen.add(value)) { continue; }
            bytes += InputContextSizeAgent.instrumentation.getObjectSize(value);
            Class<?> type = value.getClass();
            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) {
                    for (int i=0; i<Array.getLength(value); i++) {
                        Object item=Array.get(value,i); if(item!=null) queue.add(item);
                    }
                }
            } else {
                for (Class<?> current=type; current!=null; current=current.getSuperclass()) {
                    for(Field field:current.getDeclaredFields()) {
                        if(Modifier.isStatic(field.getModifiers())||field.getType().isPrimitive())continue;
                        if(!field.trySetAccessible())throw new IllegalStateException("Inaccessible field "+field);
                        Object item=field.get(value);if(item!=null)queue.add(item);
                    }
                }
            }
        }
        return bytes;
    }

    private Map<String,Object> trial(int board, String mode, int round, int iterations) throws Exception {
        Game game=initAndCreateGame(); Player ai=game.getPlayers().get(1);
        for(Player p:game.getPlayers()) {
            fillLibrary(p,30);
            for(int i=0;i<7;i++)addCardToZone("Island",p,ZoneType.Hand);
            for(int i=0;i<board;i++)addCardToZone(i%2==0?"Memnite":"Grizzly Bears",p,ZoneType.Battlefield);
        }
        SpellAbility ability=addCardToZone("Lightning Bolt",ai,ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(ai);
        game.setRecordAiDecisions(!mode.equals("DISABLED"));
        final Map<String,Object> options=Map.of("coverage","OBSERVED_RANGE","minimum",0,"maximum",7);
        final Map<String,Object> selection=Map.of("value",3,"optionIndex",-1,"domainMatch","WITHIN_OBSERVED_DOMAIN");
        final Map<String,Object>[] lastContext=new Map[]{null};
        final String[] lastSerialized={""};
        final Map<String,Long> statuses=new LinkedHashMap<>();
        game.observeEventEmission(event->{
            if(event instanceof AiChoiceEvent choice) {
                lastSerialized[0]=GSON.toJson(choice.data()); consumed=lastSerialized[0];
                if(choice.data().get("inputContext") instanceof Map<?,?> context) {
                    @SuppressWarnings("unchecked") Map<String,Object> typed=(Map<String,Object>)context;
                    lastContext[0]=typed;
                    statuses.merge((String)typed.get("status"),1L,Long::sum);
                }
            }
        });
        Runnable operation=()->{
            if(mode.equals("LEGACY_VIEW")) {
                Map<String,Object> view=AiInputContext.legacyObservation(game,ai);
                lastSerialized[0]=GSON.toJson(view);consumed=lastSerialized[0];
            } else if(mode.equals("INPUT_VIEW")) {
                Map<String,Object> context=AiInputContext.capture(ai,ability,"BEFORE_SELECTOR","RULES_STATE",()->true);
                lastContext[0]=context; lastSerialized[0]=GSON.toJson(context);consumed=lastSerialized[0];
                statuses.merge((String)context.get("status"),1L,Long::sum);
            } else if(mode.equals("BASE_CHOICE")) {
                AiChoiceRecorder.observe(ai,ability,"NUMBER_SELECTION",null,"CONTROLLER_NUMBER_RANGE",()->options,()->3,ignored->selection);
            } else {
                AiChoiceRecorder.observeInput(ai,ability,"NUMBER_SELECTION",null,"CONTROLLER_NUMBER_RANGE","BEFORE_SELECTOR",()->options,()->3,ignored->selection);
            }
        };
        for(int i=0;i<75;i++)operation.run();
        statuses.clear();
        long thread=Thread.currentThread().threadId();
        long allocation=threads.getThreadAllocatedBytes(thread),cpu=threads.getCurrentThreadCpuTime(),start=System.nanoTime();
        for(int i=0;i<iterations;i++)operation.run();
        long elapsed=System.nanoTime()-start, cpuNanos=threads.getCurrentThreadCpuTime()-cpu;
        long allocated=threads.getThreadAllocatedBytes(thread)-allocation;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("boardCardsPerPlayer",board);result.put("mode",mode);result.put("round",round);result.put("iterations",iterations);
        result.put("wallNanos",elapsed);result.put("currentThreadCpuNanos",cpuNanos);result.put("currentThreadAllocatedBytes",allocated);
        result.put("statuses",statuses);result.put("lastContextReachableBytes",reachableBytes(lastContext[0]));
        byte[] raw=lastContext[0]==null?new byte[0]:GSON.toJson(lastContext[0]).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream gzBytes=new ByteArrayOutputStream();
        try(GZIPOutputStream gzip=new GZIPOutputStream(gzBytes)){gzip.write(raw);}
        result.put("oneContextRawBytes",raw.length);result.put("oneContextGzipBytes",gzBytes.size());
        if(mode.equals("INPUT_VIEW")||mode.equals("INPUT_CHOICE")) {
            AiInputContext.State state=((PlayerControllerAi)ai.getController()).inputContextState();
            result.put("workRemaining",state.remainingWork());
            result.put("bufferedFactsReachableBytes",reachableBytes(state.disclosures().snapshot().facts()));
        }
        game.closeEventObservations();return result;
    }

    public static void main(String[] args)throws Exception {
        InputContextProbe probe=new InputContextProbe();probe.initializeModel();
        probe.threads.setThreadCpuTimeEnabled(true);probe.threads.setThreadAllocatedMemoryEnabled(true);
        List<Map<String,Object>> rows=new ArrayList<>();
        List<String> modes=List.of("DISABLED","BASE_CHOICE","INPUT_CHOICE","LEGACY_VIEW","INPUT_VIEW");
        for(int board:List.of(4,30))for(int round=-1;round<4;round++)for(int offset=0;offset<modes.size();offset++) {
            String mode=modes.get(Math.floorMod(round+offset,modes.size()));
            Map<String,Object> row=probe.trial(board,mode,round,200);
            if(round>=0){rows.add(row);Files.writeString(Path.of(args[0]),GSON.toJson(rows)+"\n");}
        }
        System.exit(0);
    }
}
