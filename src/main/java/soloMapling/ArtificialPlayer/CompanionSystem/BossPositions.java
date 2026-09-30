package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import server.StatEffect;
import server.life.Monster;
import server.maps.*;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import java.awt.*;
import java.util.*;

/** Candidate goals are real footholds inside the learned attack rectangle and human leash. */
public final class BossPositions {
    private BossPositions() {}
    public static int leashX(Character bot) { return bot.getSkillLevel(constants.skills.Cleric.HEAL)>0 ? 350 : 700; }
    public static int leashY(Character bot) { return bot.getSkillLevel(constants.skills.Cleric.HEAL)>0 ? 160 : 350; }
    static Optional<Point> engagement(Character bot, Character leader, Monster target, BotAttackProfile profile, StatEffect effect) {
        var tree=bot.getMap().getFootholds();
        if (tree==null) return Optional.empty();
        Point enemy=BossGeometry.aim(target), human=leader.getPosition(), from=bot.getPosition();
        int spacing=profile.route==BotAttackProfile.Route.CLOSE ? 40 : Math.min(220,profile.reachX/2);
        java.util.List<Point> candidates=new ArrayList<>();
        for (Foothold foothold:tree.getAllFootholds()) {
            if (foothold.isWall()) continue;
            for (int offset:new int[]{-spacing,spacing,-40,40,0}) {
                int x=Math.max(Math.min(foothold.getX1(),foothold.getX2()),Math.min(Math.max(foothold.getX1(),foothold.getX2()),enemy.x+offset));
                Point point=onFoothold(foothold,x);
                if (Math.abs(point.x-human.x)>leashX(bot) || Math.abs(point.y-human.y)>leashY(bot)
                        || !bot.getMap().getMapArea().contains(point)) continue;
                Rectangle box=effect==null ? null : effect.getAttackBox(point,enemy.x<point.x);
                if (box==null) box=new Rectangle(point.x-profile.reachX,point.y-profile.reachY,2*profile.reachX,2*profile.reachY);
                if (BossGeometry.reaches(box,target,point,profile.reachY)) candidates.add(point);
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(p->p.distanceSq(from)+Math.abs(Math.abs(p.x-enemy.x)-spacing)*100.0));
    }
    static Point onFoothold(Foothold foothold,int x) {
        double fraction=(x-foothold.getX1())/(double)(foothold.getX2()-foothold.getX1());
        return new Point(x,(int)Math.round(foothold.getY1()+fraction*(foothold.getY2()-foothold.getY1()))-1);
    }
    static Optional<Point> retreat(Character bot, Character owner) {
        var map=bot.getMap();
        // Approach the normal exit before completing the canonical instance exit or ordinary portal.
        var portal=map.getPortals().stream().filter(p->p.getPortalStatus() && p.getType()!=Portal.DOOR_PORTAL
                && p.getTargetMapId()>=0 && p.getTargetMapId()<999999999 && p.getTargetMapId()!=map.getId())
                .min(Comparator.comparingDouble(p->p.getPosition().distanceSq(bot.getPosition()))).orElse(null);
        if (portal!=null) return Optional.of(new Point(portal.getPosition()));
        if (map.getFootholds()==null || owner.getMap()!=map) return Optional.empty();
        var threats=map.getAllMonsters().stream().filter(m->m.isAlive()&&!m.isFake()&&!m.isEncounterMarker()&&m.isBoss()).toList();
        return map.getFootholds().getAllFootholds().stream().filter(f->!f.isWall())
                .map(f->onFoothold(f,Math.max(Math.min(f.getX1(),f.getX2()),Math.min(Math.max(f.getX1(),f.getX2()),owner.getPosition().x))))
                .filter(p->map.getMapArea().contains(p) && Math.abs(p.x-owner.getPosition().x)<=350
                        && Math.abs(p.y-owner.getPosition().y)<=160 && threats.stream().allMatch(m->BossGeometry.distanceSq(m,p)>=200*200))
                .min(Comparator.comparingDouble(p->p.distanceSq(bot.getPosition())));
    }
}
