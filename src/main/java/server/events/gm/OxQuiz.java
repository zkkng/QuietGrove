/* OX packet/position behavior originates from OdinMS (AGPL-3.0), author FloppyDisk. */
package server.events.gm;

import client.Character;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.TimerManager;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/** Finite, cancellable OX rounds. Human and eventual bot adapters share these authoritative rules. */
public final class OxQuiz {
    public record Question(int group,int key,int answer) {}
    private final MapleMap map;
    private final List<Question> questions;
    private final BooleanSupplier valid;
    private final IntPredicate participant;
    private final IntConsumer eliminate;
    private final Runnable finish;
    private volatile boolean cancelled;
    private volatile ScheduledFuture<?> timer;
    private long startedAtMs;
    private volatile long lastDriftMs, maxDriftMs;
    private int index;
    private long roundGeneration;
    private boolean resolving;

    /** Compatibility constructor: unregistered characters never acquire prizes from map presence. */
    public OxQuiz(MapleMap map) {
        this(map,loadQuestions(),10,System.nanoTime(),
                () -> map.getOx()!=null && map.isOxQuiz(), id -> false,id -> {},
                () -> { map.setOx(null); map.setOxQuiz(false); map.setEventStarted(false); });
    }
    public OxQuiz(MapleMap map,List<Question> catalog,int rounds,long seed,BooleanSupplier valid,
                  IntPredicate participant,IntConsumer eliminate,Runnable finish) {
        if(map==null || rounds<1 || rounds>catalog.size()) throw new IllegalArgumentException("OX catalog/rounds");
        this.map=map; this.valid=valid; this.participant=participant; this.eliminate=eliminate; this.finish=finish;
        List<Question> selected=new ArrayList<>(catalog);
        Collections.shuffle(selected,new Random(seed));
        questions=List.copyOf(selected.subList(0,rounds));
    }
    public static List<Question> loadQuestions() {
        Data data=DataProviderFactory.getDataProvider(WZFiles.ETC).getData("OXQuiz.img");
        List<Question> questions=new ArrayList<>();
        for(Data group:data.getChildren()) for(Data question:group.getChildren()) {
            try {
                int groupId=Integer.parseInt(group.getName()),key=Integer.parseInt(question.getName());
                int answer=DataTool.getInt(question.getChildByPath("a"));
                if(groupId>0 && key>0 && (answer==0 || answer==1)
                        && question.getChildByPath("q")!=null) questions.add(new Question(groupId,key,answer));
            } catch(RuntimeException invalid) { /* malformed keys are unavailable, never guessed */ }
        }
        return List.copyOf(questions);
    }
    public static boolean correct(double x,double y,int answer) {
        return y>-26 && ((answer==0 && x>-234) || (answer==1 && x<-234));
    }
    public void sendQuestion() {
        if(map.getOx()!=this || !valid.getAsBoolean()) return;
        Question question;long generation;boolean done;
        synchronized(this) {
            if(cancelled || resolving) return;
            done=index==questions.size();
            if(done) {cancelled=true;question=null;generation=0;}
            else {
                if(startedAtMs==0) startedAtMs=System.currentTimeMillis();
                question=questions.get(index);generation=++roundGeneration;
            }
        }
        if(done) {finish.run();return;}
        map.broadcastMessage(PacketCreator.showOXQuiz(question.group(),question.key(),true));
        long deadline=startedAtMs+(index+1)*30_000L;
        timer=TimerManager.getInstance().schedule(() -> resolve(question,deadline,generation),
                Math.max(1,deadline-System.currentTimeMillis()));
        if(cancelled && timer!=null) timer.cancel(false);
    }
    private void resolve(Question question,long deadline,long generation) {
        if(cancelled || map.getOx()!=this || !valid.getAsBoolean()) return;
        synchronized(this) {
            if(cancelled || resolving || generation!=roundGeneration) return;
            resolving=true;roundGeneration++;
        }
        try {
        lastDriftMs=Math.max(0,System.currentTimeMillis()-deadline);
        maxDriftMs=Math.max(maxDriftMs,lastDriftMs);
        map.broadcastMessage(PacketCreator.showOXQuiz(question.group(),question.key(),false));
        for(Character actor:new ArrayList<>(map.getCharacters())) {
            if(cancelled || !valid.getAsBoolean()) return;
            if(actor==null || actor.getMap()!=map || !participant.test(actor.getId())) continue;
            if(!actor.isAlive() || actor.isGM() || !correct(actor.getPosition().x,actor.getPosition().y,question.answer()))
                eliminate.accept(actor.getId());
            else actor.gainExp(200,true,true);
        }
        synchronized(this) {index++;}
        } finally {synchronized(this) {resolving=false;}}
        sendQuestion();
    }
    public void cancel() { synchronized(this) {cancelled=true;roundGeneration++;} if(timer!=null) timer.cancel(false); }
    public int completedRounds() { return index; }
    /** Public question identity only. Bot decisions never receive the answer value. */
    public int publicRound() { return cancelled?-1:questions.get(Math.min(index,questions.size()-1)).group()*10000+questions.get(Math.min(index,questions.size()-1)).key(); }
    public List<Question> selectedQuestions() { return questions; }
    public long lastDriftMs() { return lastDriftMs; }
    public long maxDriftMs() { return maxDriftMs; }
}
