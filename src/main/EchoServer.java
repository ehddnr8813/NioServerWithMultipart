package main;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import main.httpserver.HttpRequest;
import main.httpserver.HttpResponse;
import main.httpserver.HttpServer;

public class EchoServer {

    public static void main(final String[] args) throws Exception {
        final HttpServer server = new HttpServer("127.0.0.1", 8080, EchoServer::handle);
        server.start();
        System.in.read();
        server.stop();
    }

    static HttpResponse handle(final HttpRequest request) throws Exception {
        final int statusCode = 200;
        final String reasonPhrase = "OK";
        final Map<String, List<String>> headers = new HashMap<>();
        headers.computeIfAbsent("Content-Type", key -> new ArrayList<>())
                .add("image/png");
        String s;
        if (request.method.equals("GET")) {
            s = request.requestTarget;
        } else {
            s = new String(request.entity.array());
        }
        File file= new File("src/resource/airplane.png");
        byte[] bytes= new byte[(int)file.length()];

        FileInputStream fileInputStream = new FileInputStream(file);
        BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
        bufferedInputStream.read(bytes, 0, bytes.length);

        final ByteBuffer entity = ByteBuffer.wrap(bytes);
        return new HttpResponse(statusCode, reasonPhrase, headers, entity);
    }
}