package server.statistics;
import client.Character;
import client.Client;
import client.BotClient;
import client.QuestStatus;
import client.inventory.Item;
import com.zaxxer.hikari.*;
import config.YamlConfig;
import scripting.AbstractPlayerInteraction;
import scripting.npc.NPCConversationManager;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.quest.Quest;
import org.slf4j.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorldStatistics {
    private static final Logger LOG=LoggerFactory.getLogger(WorldStatistics.class);
    public static final BoundedStatisticsRecorder RECORDER=new BoundedStatisticsRecorder(65536);
    private static final AtomicBoolean STARTED=new AtomicBoolean();
    private static final ThreadLocal<Context> CONTEXT=ThreadLocal.withInitial(Context::new);
    private static final int[] QUEST_AREA=new int[65536];
    private static final Map<String,Integer> PQ_IDS=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer,Integer> JQ_MAPS=new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean running;
    private static Thread thread;
    private static final String EPOCH="world-2026-09-30";
    public static final class Context { public int code; public Object actor; }
    public static Context context(){return CONTEXT.get();}
    public static void scope(int code,Object actor){
        Context ctx=context();
        if((ctx.code & 65536)!=0)return;
        if((ctx.code & 65535)==0)ctx.code=code;
        if(actor!=null)ctx.actor=actor;
    }
    public static Object actor(Object client){return client instanceof Client c?c.getPlayer():null;}
    public static Object scriptActor(Object script){return ((AbstractPlayerInteraction)script).getPlayer();}
    public static void quantityChanged(Object object,int before){
        Context ctx=context();
        if(ctx.code==0 || (ctx.code & 65536)!=0 || !(ctx.actor instanceof Character chr))return;
        Item item=(Item)object;
        if(item.getItemId()/1000000!=2)return;
        int spent=Math.max(0,before)-Math.max(0,item.getQuantity());
        if(spent>0)offer(1,chr,item.getItemId(),chr.getMapId(),ctx.code&255,(ctx.code>>>8)&255,spent);
    }
    public static void offer(int metric,Character chr,int entity,int region,int reason,int method,long amount){
        if(chr==null || (context().code&65536)!=0)return;
        RECORDER.offer(metric,Math.max(0,chr.getWorld()),chr.getClient() instanceof BotClient?1:0,
                2,Math.max(0,entity),Math.max(0,region),reason,method,System.currentTimeMillis(),amount);
    }
    public static void monster(Object obj,Object killer){
        Monster mob=(Monster)obj;
        if(killer instanceof Character chr && mob.getHp()==0)offer(2,chr,mob.getId(),chr.getMapId(),0,0,1);
    }
    public static int questBefore(Object obj,Object player){
        return ((Character)player).getQuest((Quest)obj).getStatus()==QuestStatus.Status.COMPLETED?1:0;
    }
    public static void questAfter(Object obj,Object player,int before){
        Character chr=(Character)player; Quest quest=(Quest)obj;
        if(before==0 && chr.getQuest(quest).getStatus()==QuestStatus.Status.COMPLETED){
            int id=Short.toUnsignedInt(quest.getId());offer(3,chr,id,QUEST_AREA[id],0,5,1);
        }
    }
    public static void death(Object obj,int oldHp){
        Character chr=(Character)obj;if(oldHp>0 && chr.getHp()<=0)offer(4,chr,0,chr.getMapId(),0,0,1);
    }
    public record PqClear(int id,List<Character> players) {}
    public static Object pqBefore(Object obj){
        EventInstanceManager eim=(EventInstanceManager)obj;
        if(eim.isEventCleared() || eim.getEm()==null)return null;
        Integer id=PQ_IDS.get(eim.getEm().getName());return id==null?null:new PqClear(id,eim.getPlayers());
    }
    public static void pqAfter(Object obj,Object captured){
        if(!(captured instanceof PqClear clear) || !((EventInstanceManager)obj).isEventCleared()
                || clear.players().isEmpty() || (context().code&65536)!=0)return;
        Character first=clear.players().get(0);int population=first.getClient() instanceof BotClient?1:0;
        for(Character chr:clear.players())if((chr.getClient() instanceof BotClient?1:0)!=population){population=2;break;}
        RECORDER.offer(5,first.getWorld(),population,2,clear.id(),first.getMapId(),0,5,System.currentTimeMillis(),1);
        for(Character chr:clear.players())offer(6,chr,clear.id(),chr.getMapId(),0,5,1);
    }
    public static int jqBefore(Object obj){
        if(!(obj instanceof NPCConversationManager cm))return 0;
        Integer map=JQ_MAPS.get(cm.getNpc());return map!=null && cm.getPlayer().getMapId()==map?cm.getNpc():0;
    }
    public static void jqAfter(Object obj,int npc,int oldMap){
        AbstractPlayerInteraction api=(AbstractPlayerInteraction)obj;
        if(npc!=0 && api.getPlayer().getMapId()!=oldMap)offer(7,api.getPlayer(),npc,oldMap,0,5,1);
    }
    public static void start(){
        if(Boolean.getBoolean("world.stats.disabled") || !STARTED.compareAndSet(false,true))return;
        running=true;thread=new Thread(WorldStatistics::run,"world-statistics");thread.setDaemon(true);thread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            RECORDER.setEnabled(false);running=false;
            try{thread.join(6000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        },"world-statistics-shutdown"));
    }
    public static HikariDataSource dataSource(){
        HikariConfig cfg=new HikariConfig();String override=System.getenv("DB_HOST");
        cfg.setJdbcUrl(String.format(YamlConfig.config.server.DB_URL_FORMAT,override!=null?override:YamlConfig.config.server.DB_HOST));
        cfg.setUsername(YamlConfig.config.server.DB_USER);cfg.setPassword(YamlConfig.config.server.DB_PASS);
        cfg.setPoolName("world-statistics");cfg.setMaximumPoolSize(1);cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(2000);cfg.setValidationTimeout(1000);cfg.setInitializationFailTimeout(-1);
        cfg.addDataSourceProperty("connectTimeout","2000");cfg.addDataSourceProperty("socketTimeout","3000");
        cfg.setConnectionInitSql("SET SESSION sql_mode=CONCAT(@@sql_mode,',STRICT_ALL_TABLES')");
        return new HikariDataSource(cfg);
    }
    public static void schema(HikariDataSource ds)throws Exception{
        String sql;
        try(InputStream in=WorldStatistics.class.getResourceAsStream("/world-statistics/schema.sql")){
            if(in==null)throw new IOException("Missing stats schema");sql=new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
        try(Connection c=ds.getConnection();Statement s=c.createStatement()){
            s.setQueryTimeout(3);for(String statement:sql.split(";"))if(!statement.isBlank())s.execute(statement);
        }
    }
    private static void catalog(HikariDataSource ds)throws Exception{
        try(var reader=new BufferedReader(new InputStreamReader(Objects.requireNonNull(WorldStatistics.class.getResourceAsStream("/world-statistics/catalog.tsv")),StandardCharsets.UTF_8));
            Connection c=ds.getConnection();
            PreparedStatement s=c.prepareStatement("INSERT INTO stats_entity(kind,entity_id,name,category,region_id) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),category=VALUES(category),region_id=VALUES(region_id)")){
            s.setQueryTimeout(3);int count=0;
            for(String line;(line=reader.readLine())!=null;){
                String[] f=line.split("\\t",-1);int id=Integer.parseInt(f[1]),area=Integer.parseInt(f[4]);
                if(f[0].equals("quest") && id<QUEST_AREA.length)QUEST_AREA[id]=area;
                if(f[0].equals("pq"))PQ_IDS.put(f[2],id);if(f[0].equals("jq"))JQ_MAPS.put(id,area);
                s.setString(1,f[0]);s.setInt(2,id);s.setString(3,f[2]);s.setString(4,f[3]);s.setInt(5,area);s.addBatch();
                if(++count%500==0)s.executeBatch();
            }s.executeBatch();
        }
    }
    private static void epoch(HikariDataSource ds,String epoch)throws SQLException{
        try(Connection c=ds.getConnection();PreparedStatement s=c.prepareStatement("INSERT IGNORE INTO stats_epoch(epoch_id,started_at,environment_name) VALUES(?,?,?)")){
            s.setQueryTimeout(3);s.setString(1,epoch);s.setLong(2,System.currentTimeMillis());s.setString(3,epoch.equals(EPOCH)?"live":"isolated-db-check");s.executeUpdate();
        }
    }
    private static void run(){
        Path dir=Path.of("data","world-statistics");
        try{Files.createDirectories(dir);}catch(IOException e){LOG.error("World statistics spool unavailable",e);return;}
        while(running){
            try(HikariDataSource ds=dataSource()){
                schema(ds);catalog(ds);epoch(ds,EPOCH);
                Path producerFile=dir.resolve("producer.id");
                if(!Files.exists(producerFile))Files.writeString(producerFile,UUID.randomUUID().toString(),StandardOpenOption.CREATE_NEW);
                UUID producer=UUID.fromString(Files.readString(producerFile).trim());
                try(StatisticsJournal journal=new StatisticsJournal(dir.resolve("journal.bin"),64L*1024*1024)){
                    StatisticsAccumulator accumulator=new StatisticsAccumulator(100000);
                    StatisticsWorker worker=new StatisticsWorker(RECORDER,accumulator,journal,new JdbcStatisticsBatchSink(ds,2),EPOCH,producer);
                    worker.replay();
                    try(Connection c=ds.getConnection();Statement s=c.createStatement()){
                        s.setQueryTimeout(3);s.executeUpdate("UPDATE stats_metric SET activation_at=COALESCE(activation_at,"+System.currentTimeMillis()+"),availability='partial'");
                        try(PreparedStatement q=c.prepareStatement("INSERT INTO stats_coverage(epoch_id,metric_id,started_at,coverage_state,reason) VALUES(?,0,?,'partial','core adapters active; crash window and unsupported activity paths disclosed')")){
                            q.setString(1,EPOCH);q.setLong(2,System.currentTimeMillis());q.executeUpdate();
                        }
                    }
                    RECORDER.setEnabled(running);LOG.info("WORLD_STATS_ACTIVE epoch={} producer={} core metrics=7; coverage=partial",EPOCH,producer);
                    long nextHealth=0,nextRetry=0;
                    try{
                        while(running){
                            long now=System.currentTimeMillis();worker.drain(10000);
                            if(now>=nextRetry){
                                try{
                                    for(int i=0;i<32 && worker.flushOne();i++){}
                                    if(accumulator.size()==0 && !worker.hasPendingBatch() && journal.size()>8L*1024*1024)journal.resetAcknowledged();
                                    nextRetry=0;
                                }catch(IOException e){nextRetry=now+5000;LOG.warn("World statistics persistence retry: {}",e.getMessage());}
                            }
                            if(now>=nextHealth){health(dir,worker,accumulator,now);nextHealth=now+30000;}
                            Thread.sleep(250);
                        }
                    }finally{RECORDER.setEnabled(false);worker.drain(65536);worker.persistRemainingForShutdown();health(dir,worker,accumulator,System.currentTimeMillis());}
                }return;
            }catch(Exception e){
                LOG.error("World statistics initialization/recovery paused; gameplay continues: {}",e.toString());RECORDER.setEnabled(false);
                try{Thread.sleep(5000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
            }
        }
    }
    private static void health(Path dir,StatisticsWorker worker,StatisticsAccumulator accumulator,long now)throws IOException{
        var h=RECORDER.health();
        String json="{\"epoch\":\""+EPOCH+"\",\"at\":"+now+",\"enabled\":"+RECORDER.isEnabled()+",\"accepted\":"+h.accepted()+",\"queued\":"+h.queued()+",\"rejectedByMetric\":"+Arrays.toString(h.rejectedByMetric())+",\"accumulatorDropped\":"+accumulator.droppedFacts()+",\"committedBatches\":"+worker.committedBatches()+",\"lastDurableAt\":"+worker.lastDurableAt()+",\"coverage\":\"partial\"}\n";
        Path temp=dir.resolve("health.tmp");Files.writeString(temp,json);
        Files.move(temp,dir.resolve("health.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        LOG.info("WORLD_STATS_HEALTH accepted={} rejected={} accumulatorDropped={} committedBatches={}",h.accepted(),Arrays.toString(h.rejectedByMetric()),accumulator.droppedFacts(),worker.committedBatches());
    }
    public static void main(String[] args)throws Exception{
        try(HikariDataSource ds=dataSource()){
            if(args.length==1 && args[0].equals("db-check")){schema(ds);catalog(ds);dbCheck(ds);return;}
            if(args.length!=1 || !args[0].equals("inspect"))throw new IllegalArgumentException("db-check or inspect");
            try(Connection c=ds.getConnection();Statement s=c.createStatement()){
                s.setQueryTimeout(3);
                try(ResultSet r=s.executeQuery("SELECT m.metric_key,COALESCE(SUM(a.value),0) AS total FROM stats_metric m LEFT JOIN stats_aggregate a ON a.metric_id=m.metric_id AND a.definition_version=m.definition_version AND a.projection_id=0 AND a.day_bucket=-1 AND a.epoch_id='"+EPOCH+"' GROUP BY m.metric_id,m.metric_key ORDER BY m.metric_id")){
                    while(r.next())System.out.println(r.getString(1)+"="+r.getString(2));
                }
                try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM stats_batch_receipt WHERE epoch_id='"+EPOCH+"'")){r.next();System.out.println("durable_batches="+r.getLong(1));}
            }
        }
    }
    private static void dbCheck(HikariDataSource ds)throws Exception{
        String epoch="ws-check-"+UUID.randomUUID();epoch(ds,epoch);
        UUID producer=UUID.randomUUID(),boot=UUID.randomUUID();long now=System.currentTimeMillis();
        var key=new StatisticsAccumulator.Key(1,1,0,0,2,2000000,0,0,0,-1);
        BigInteger value=new BigInteger("9007199254741000");
        var batch=new StatisticsBatch(epoch,producer,boot,1,now,now,List.of(new StatisticsAccumulator.Delta(key,value)));
        JdbcStatisticsBatchSink sink=new JdbcStatisticsBatchSink(ds,2);
        try{
            if(!sink.apply(batch) || sink.apply(batch))throw new AssertionError("Receipt replay");
            try{sink.apply(new StatisticsBatch(epoch,producer,boot,1,now,now,List.of(new StatisticsAccumulator.Delta(key,value.add(BigInteger.ONE)))));throw new AssertionError("Checksum conflict accepted");}catch(IOException expected){}
            try(Connection c=ds.getConnection();PreparedStatement q=c.prepareStatement("SELECT value FROM stats_aggregate WHERE epoch_id=?")){
                q.setString(1,epoch);try(ResultSet r=q.executeQuery()){if(!r.next() || !r.getBigDecimal(1).toBigIntegerExact().equals(value))throw new AssertionError("Exact value");}
                try(PreparedStatement s=c.prepareStatement("UPDATE stats_aggregate SET value=? WHERE epoch_id=?")){s.setBigDecimal(1,new java.math.BigDecimal(StatisticsAccumulator.MAX_VALUE));s.setString(2,epoch);s.executeUpdate();}
            }
            try{sink.apply(new StatisticsBatch(epoch,producer,boot,2,now,now,List.of(new StatisticsAccumulator.Delta(key,BigInteger.ONE))));throw new AssertionError("Overflow clipped");}catch(IOException expected){}
            try(Connection c=ds.getConnection();PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM stats_batch_receipt WHERE epoch_id=?")){q.setString(1,epoch);try(ResultSet r=q.executeQuery()){r.next();if(r.getInt(1)!=1)throw new AssertionError("Failed receipt not rolled back");}}
            System.out.println("WORLD_STATS_DB_CHECK_PASS exact-decimal/replay/conflict/overflow/rollback");
        }finally{
            try(Connection c=ds.getConnection()){for(String table:List.of("stats_batch_receipt","stats_aggregate","stats_epoch"))try(PreparedStatement s=c.prepareStatement("DELETE FROM "+table+" WHERE epoch_id=?")){s.setString(1,epoch);s.executeUpdate();}}
        }
    }
}
