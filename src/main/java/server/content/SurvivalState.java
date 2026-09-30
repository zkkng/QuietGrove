package server.content;

/** Pure scoring state: skill uses and results can only be consumed once. */
public final class SurvivalState {
    private int kills,cools,misses,uses,buff;
    private boolean paid;
    public synchronized void hit(boolean cool) {kills++;if(cool)cools++;}
    public synchronized void miss() {misses++;}
    public synchronized int kills(){return kills;}
    public synchronized int cools(){return cools;}
    public synchronized int misses(){return misses;}
    public synchronized int skillUses(){return Math.max(0,Math.min(6,(kills+cools)/500)-uses);}
    public synchronized boolean useSkill(){if(skillUses()==0)return false;uses++;return true;}
    public synchronized int nextBuff(boolean subway){
        int score=kills+cools;
        int next=subway?(score>=300?2:score>=100?1:0):(score>=2000?4:score>=1000?3:score>=500?2:score>=250?1:0);
        if(next<=buff)return 0;buff=next;
        return subway?2022615+next:2022584+next;
    }
    public synchronized byte rank(boolean cleared,boolean subway){
        if(!cleared)return 4;
        int score=kills+cools-misses;
        if(subway)return (byte)(score>=1000?0:score>=700?1:score>=400?2:3);
        return (byte)(score>=3000?0:score>=2000?1:score>=1000?2:3);
    }
    public synchronized int experience(boolean cleared,boolean subway,int mode){
        int base=switch(rank(cleared,subway)){case 0->60500+5500*mode;case 1->55000+5000*mode;case 2->46750+4250*mode;case 3->22000+2000*mode;default->0;};
        if(subway)base/=3;
        return base+kills*2+cools*10;
    }
    public synchronized boolean claim(){if(paid)return false;paid=true;return true;}
}
