package server.trainer;

import client.Character;
import server.maps.MapItem;
import server.life.Monster;
import java.util.*;

/** Read-only visible-world objects. No player/admin identities or private bot state. */
final class TrainerInspector {
    private TrainerInspector() { }
    static final int PAGE=8;
    static String name(String text) { return text==null?"unknown":text.replaceAll("[\\p{Cntrl}]"," ").substring(0,Math.min(24,text.length())); }
    static Map<String,String> snapshot(Character actor,Map<String,String> fields) {
        if(!fields.keySet().equals(Set.of("kind","offset","map"))) throw new IllegalArgumentException("Choose inspector kind/page/map");
        var map=actor.getMap();
        if(Integer.parseInt(fields.get("map"))!=map.getId()) throw new IllegalArgumentException("Map changed; refresh current map first");
        int offset=Integer.parseInt(fields.get("offset"));
        if(offset<0||offset>100000) throw new IllegalArgumentException("Invalid inspector offset");
        List<String> rows=new ArrayList<>(); String kind=fields.get("kind"); int total;
        switch(kind) {
            case "mobs" -> { var all=map.getMonsters().stream().map(o->(Monster)o).filter(Monster::isAlive).sorted(Comparator.comparingInt(Monster::getObjectId)).toList(); total=all.size(); all.stream().skip(offset).limit(PAGE).forEach(m->rows.add(m.getObjectId()+"\t"+m.getId()+"\t"+name(m.getName())+"\t"+m.getPosition().x+"\t"+m.getPosition().y+"\t"+m.getHp()+"/"+m.getMaxHp()+" HP\t"+Math.round(actor.getPosition().distance(m.getPosition())))); }
            case "drops" -> { var all=map.getItems().stream().filter(o->o instanceof MapItem).map(o->(MapItem)o).filter(d->!d.isPickedUp()).sorted(Comparator.comparingInt(MapItem::getObjectId)).toList(); total=all.size(); all.stream().skip(offset).limit(PAGE).forEach(d->{
                    long value=TrainerLootAdvanced.value(d);
                    String label=d.getMeso()>0?"mesos":server.ItemInformationProvider.getInstance().getName(d.getItemId());
                    rows.add(d.getObjectId()+"\t"+d.getItemId()+"\t"+name(label)+"\t"+d.getPosition().x+"\t"+d.getPosition().y+"\t"+(value<0?"value unknown":"est. "+value)+"\t"+Math.round(actor.getPosition().distance(d.getPosition())));
                }); }
            case "portals" -> { var all=map.getPortals().stream().sorted(Comparator.comparingInt(server.maps.Portal::getId)).toList(); total=all.size(); all.stream().skip(offset).limit(PAGE).forEach(p->
                rows.add(p.getId()+"\t"+p.getTargetMapId()+"\t"+name(p.getName())+"\t"+p.getPosition().x+"\t"+p.getPosition().y+"\t"+(p.getPortalStatus()?"open":"closed")+"\t"+Math.round(actor.getPosition().distance(p.getPosition())))); }
            case "footholds" -> { var all=map.getFootholds().getAllFootholds().stream().sorted(Comparator.comparingInt(server.maps.Foothold::getId)).toList(); total=all.size(); all.stream().skip(offset).limit(PAGE).forEach(f->
                rows.add(f.getId()+"\t0\tfoothold\t"+f.getX1()+"\t"+f.getY1()+"\t"+f.getX2()+","+f.getY2()+"\t0")); }
            default -> throw new IllegalArgumentException("Unknown inspector kind");
        }
        var r=new LinkedHashMap<String,String>(); var area=map.getMapArea();
        r.put("protocol","SoloMapling-Trainer-v3"); r.put("map",Integer.toString(map.getId())); r.put("kind",kind);
        r.put("area",area.x+","+area.y+","+area.width+","+area.height);
        r.put("actor",actor.getPosition().x+","+actor.getPosition().y); r.put("total",Integer.toString(total));
        r.put("offset",Integer.toString(offset)); int count=0;
        for(int i=0;i<rows.size()&&count<PAGE;i++) r.put("row"+count++,rows.get(i));
        r.put("count",Integer.toString(count));
        if(actor.getMap()!=map) throw new IllegalArgumentException("Map changed during inspection; refresh again");
        return r;
    }
}
