package server.events.gm;

import provider.wz.WZFiles;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Ellipse2D;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/** The moving ball's body comes from the local canvas dimensions and origin, not combat reach. */
final class SnowballGeometry {
    private static final ConcurrentHashMap<Path,Rectangle> bodies=new ConcurrentHashMap<>();
    private final Rectangle body;
    SnowballGeometry() {
        Path file=WZFiles.MAP.getFile().resolve("Obj/event.img.xml").toAbsolutePath().normalize();
        body=new Rectangle(bodies.computeIfAbsent(file,SnowballGeometry::load));
    }
    private static Rectangle load(Path file) {
        try {
            var factory=DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            var document=factory.newDocumentBuilder().parse(file.toFile());
            var canvas=(Element)XPathFactory.newInstance().newXPath().evaluate(
                    "/imgdir/imgdir[@name='snowyRock']/imgdir[@name='snowball']/imgdir[@name='0']/canvas[@name='0']",
                    document,javax.xml.xpath.XPathConstants.NODE);
            if(canvas==null) throw new IllegalArgumentException("Missing Snowball canvas");
            var origin=(Element)canvas.getElementsByTagName("vector").item(0);
            int width=Integer.parseInt(canvas.getAttribute("width")),height=Integer.parseInt(canvas.getAttribute("height"));
            if(origin==null || width<=0 || height<=0) throw new IllegalArgumentException("Invalid Snowball body");
            return new Rectangle(-Integer.parseInt(origin.getAttribute("x")),-Integer.parseInt(origin.getAttribute("y")),width,height);
        } catch(Exception failure) {throw new IllegalArgumentException("Cannot load Snowball collision geometry",failure);}
    }
    boolean touches(Point ball,Point previous,Point current) {
        if(previous==null) previous=current;
        var swept=new Rectangle(Math.min(previous.x,current.x)-10,Math.min(previous.y,current.y)-50,
                Math.abs(current.x-previous.x)+20,Math.abs(current.y-previous.y)+50);
        return new Ellipse2D.Double(ball.x+body.x,ball.y+body.y,body.width,body.height).intersects(swept);
    }
    int approachOffset() {return Math.max(100,-body.x+35);}
}
