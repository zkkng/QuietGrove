/* Snowball packet behavior originates from OdinMS (AGPL-3.0), author kevintjuh93. */
package server.events.gm;

import server.maps.MapleMap;

/** Packet-facing team state. All changes are serialized by the shared SnowballGame. */
public final class Snowball {
    private final int team;
    private volatile int position;
    private volatile int snowmanHp=7500;
    private volatile boolean hittable;
    int hits=3;
    long recoveryAt;
    public Snowball(int team,MapleMap map) {
        if(team<0 || team>1) throw new IllegalArgumentException("team");
        this.team=team;
    }
    public int getTeam() { return team; }
    public int getPosition() { return position; }
    public int getSnowmanHP() { return snowmanHp; }
    public boolean isHittable() { return hittable; }
    void position(int position) { this.position=position; }
    void snowmanHp(int hp) { snowmanHp=hp; }
    void hittable(boolean value) { hittable=value; }
}
