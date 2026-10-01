package provider.wz;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XMLDomMapleDataConcurrencyTest {
    @Test
    void wrappersOfOneDocumentSerializeDomReads() throws Exception {
        Document document = (Document) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Document.class},
                (proxy, method, args) -> null);
        NodeList empty = new NodeList() {
            @Override public Node item(int index) { return null; }
            @Override public int getLength() { return 0; }
        };
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        Node node = (Node) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Node.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getOwnerDocument" -> document;
                    case "getChildNodes" -> {
                        int current = active.incrementAndGet();
                        maximum.accumulateAndGet(current, Math::max);
                        try {
                            Thread.sleep(60);
                        } finally {
                            active.decrementAndGet();
                        }
                        yield empty;
                    }
                    default -> null;
                });

        Constructor<XMLDomMapleData> constructor = XMLDomMapleData.class.getDeclaredConstructor(Node.class);
        constructor.setAccessible(true);
        int readers = 4;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(readers)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < readers; i++) {
                XMLDomMapleData wrapper = constructor.newInstance(node);
                tasks.add(executor.submit(() -> {
                    start.await();
                    wrapper.getChildren();
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, maximum.get(), "one document must not be traversed by multiple wrappers at once");
    }
}
