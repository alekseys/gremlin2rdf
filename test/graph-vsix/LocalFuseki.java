import java.nio.file.*;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.DatasetFactory;

public final class LocalFuseki {
    public static void main(String[] args) throws Exception {
        var dataset = DatasetFactory.createTxnMem();
        var server = FusekiServer.create().loopback(true).port(0).add("/fixture", dataset).build().start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(); dataset.close(); }));
        Files.writeString(Path.of(args[0]), "{\"port\":" + server.getPort() + "}");
        System.in.read();
        server.stop();
        dataset.close();
    }
}
