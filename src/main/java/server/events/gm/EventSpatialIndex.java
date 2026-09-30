package server.events.gm;

import java.awt.Point;
import java.util.*;
import java.util.function.Function;

/** Immutable one-tick position snapshot. Neighborhood queries inspect intersecting cells only. */
final class EventSpatialIndex<T> {
    private record Cell(int x,int y) {}
    private record Located<T>(T value,Point point) {}
    private final int cellSize;
    private final Map<Cell,List<Located<T>>> cells=new HashMap<>();
    EventSpatialIndex(Collection<T> values,Function<T,Point> position,int cellSize) {
        if(cellSize<1) throw new IllegalArgumentException("cell size");this.cellSize=cellSize;
        for(T value:values) {
            Point point=new Point(position.apply(value));
            cells.computeIfAbsent(new Cell(Math.floorDiv(point.x,cellSize),Math.floorDiv(point.y,cellSize)),ignored->new ArrayList<>()).add(new Located<>(value,point));
        }
    }
    List<T> nearby(Point point,int radius) {
        if(radius<0) throw new IllegalArgumentException("radius");
        List<T> found=new ArrayList<>();long squared=(long)radius*radius;
        int minX=Math.floorDiv(point.x-radius,cellSize),maxX=Math.floorDiv(point.x+radius,cellSize);
        int minY=Math.floorDiv(point.y-radius,cellSize),maxY=Math.floorDiv(point.y+radius,cellSize);
        for(int x=minX;x<=maxX;x++) for(int y=minY;y<=maxY;y++)
            for(var entry:cells.getOrDefault(new Cell(x,y),List.of()))
                if(entry.point().distanceSq(point)<=squared) found.add(entry.value());
        return found;
    }
}
