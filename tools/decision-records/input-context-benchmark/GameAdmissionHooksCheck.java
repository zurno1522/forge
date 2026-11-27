package forge.ai;

import forge.deck.Deck;
import forge.game.*;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Executable unit checks for the isolated meter, using real State charges and native entry guards. */
public final class GameAdmissionHooksCheck extends AITest {
    private static void check(boolean b,String m){if(!b){throw new AssertionError(m);}}
    private static GameAdmissionExperiment.Actor actor(AiInputContext.State s)throws Exception{
        Field f=AiInputContext.State.class.getDeclaredField("experimentActor");f.setAccessible(true);return (GameAdmissionExperiment.Actor)f.get(s);
    }
    private static boolean charge(AiInputContext.State s)throws Exception{
        Method m=AiInputContext.State.class.getDeclaredMethod("acquireWork");m.setAccessible(true);return (Boolean)m.invoke(s);
    }
    public static void main(String[] args){try{new GameAdmissionHooksCheck().run();System.exit(0);}catch(Throwable failure){failure.printStackTrace(System.err);System.exit(1);}}
    private void run()throws Exception{
        initializeModel();List<RegisteredPlayer> registered=new ArrayList<>();for(int i=0;i<4;i++){registered.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("p"+i,null)));}
        GameRules rules=new GameRules(GameType.Commander);Game game=new Game(registered,rules,new Match(rules,registered,"meter units"));game.setAge(GameStage.Play);Player chooser=game.getPlayers().get(1);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,chooser);
        SpellAbility sa=addCardToZone("Lightning Bolt",chooser,ZoneType.Hand).getFirstSpellAbility();sa.setActivatingPlayer(chooser);game.setRecordAiDecisions(true);
        List<AiInputContext.State> states=new ArrayList<>();for(Player p:game.getPlayers()){states.add(((PlayerControllerAi)p.getController()).inputContextState());}
        AiInputContext.State state=states.get(1);var a=actor(state);String policy=System.getProperty("forge.admission.policy");
        long initial=state.remainingWork(),before=a.entered;
        AiInputContext.capture(chooser,sa,"BEFORE_OPERATION","RULES_STATE",()->false);check(a.entered==before&&state.remainingWork()==initial,"Closed guard entered meter");
        game.setRecordAiDecisions(false);AiInputContext.capture(chooser,sa,"BEFORE_OPERATION","RULES_STATE",()->true);check(a.entered==before&&state.remainingWork()==initial,"Disabled guard entered meter");game.setRecordAiDecisions(true);
        if(policy.equals("HASH8")){
            List<Integer> admitted=new ArrayList<>();for(int i=1;i<=32;i++){var ctx=AiInputContext.capture(chooser,sa,"BEFORE_OPERATION","RULES_STATE",()->true);if(ctx.get("policy")!=null){admitted.add(i);}}
            check(admitted.equals(List.of(7,8,17)),"Hash admission differs from fixed player-1/salt-0 vector");check(a.entered==32&&a.returned==32,"Sampling dropped invocations");
        }else if(policy.equals("RESERVE")){
            check(initial==32000&&a.held==32000,"Wrong first tranche");int paid=0;while(charge(state)){paid++;}check(paid==32000&&a.outsideWork==32000,"Outside work not charged exactly");
            var omitted=AiInputContext.capture(chooser,sa,"BEFORE_OPERATION","RULES_STATE",()->true);check(((Map<?,?>)((List<?>)omitted.get("omissions")).get(0)).get("reason").equals("EXPERIMENT_RESERVED"),"Reserve omission lost");
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1,chooser,31);check(a.held==0&&a.funded==64000&&state.remainingWork()==32000,"Public turn did not release reserve");game.getPhaseHandler().devModeSet(PhaseType.MAIN1,chooser,31);check(state.remainingWork()==32000,"Reserve released twice");
            check(AiInputContext.capture(chooser,sa,"BEFORE_OPERATION","RULES_STATE",()->true).get("policy")!=null,"Released capture unavailable");
        }else{
            check(AiInputContext.capture(chooser,sa,"BEFORE_OPERATION","RULES_STATE",()->true).get("policy")!=null,"Prefix unavailable");
            RuntimeException marker=new IllegalStateException("original");try{AiInputContext.capture(chooser,sa,"BEFORE_OPERATION","RULES_STATE",()->true,null,()->{throw marker;});throw new AssertionError("Original failure lost");}catch(RuntimeException actual){check(actual==marker,"Original failure replaced");}
            check(a.threw==1&&a.failedWork>0&&a.inFlight==0,"Failed capture receipt/debits missing");
        }
        check(a.captureWork+a.outsideWork+state.remainingWork()==a.funded,"Owned/outside work fails conservation");
        var pendingState=states.get(3);var pending=actor(pendingState);Player pendingPlayer=game.getPlayers().get(3);CountDownLatch ready=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread worker=new Thread(()->{try{
            GameAdmissionExperiment.Ticket ticket;
            while(true){ticket=GameAdmissionExperiment.begin(pendingPlayer,pendingState,pending,"BEFORE_OPERATION","RULES_STATE");if(ticket.reason==null){break;}GameAdmissionExperiment.finish(ticket,AiInputContext.unavailable(pendingPlayer,"BEFORE_OPERATION","RULES_STATE",ticket.reason));}
            for(int i=0;i<10;i++){check(charge(pendingState),"Missing pending debit");}ready.countDown();check(release.await(5,TimeUnit.SECONDS),"Unit release timeout");GameAdmissionExperiment.finish(ticket,AiInputContext.unavailable(pendingPlayer,"BEFORE_OPERATION","RULES_STATE","INTERRUPTED"));
        }catch(Throwable t){failure.set(t);ready.countDown();}},"pending-capture");worker.start();check(ready.await(5,TimeUnit.SECONDS),"Unit entry timeout");if(failure.get()!=null){throw new AssertionError(failure.get());}
        Map<String,Object> saved;synchronized(pending){saved=pending.seal();}long entered=pending.entered;check(saved.get("inFlight").equals(1)&&saved.get("inFlightWork").equals(10L),"Cutoff invented completion");
        release.countDown();worker.join(5000);check(!worker.isAlive()&&failure.get()==null,"Pending worker failed");check(pending.entered==entered&&saved.get("inFlight").equals(1),"Late receipt rewrote cutoff");
        var late=AiInputContext.capture(pendingPlayer,sa,"BEFORE_OPERATION","RULES_STATE",()->true);check(late.get("status").equals("UNAVAILABLE")&&pending.entered==entered,"Late capture re-entered sealed scope");
        check((long)saved.get("captureWork")+(long)saved.get("outsideCaptureWork")+(long)saved.get("remaining")== (long)saved.get("funded"),"Cutoff debits not atomic");
        System.out.println("PASS "+policy+" guards, native accounting/admission, and in-flight/late cutoff checks");
    }
}
