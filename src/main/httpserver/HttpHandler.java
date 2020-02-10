package main.httpserver;

public interface HttpHandler {
    HttpResponse handle(HttpRequest request) throws Exception;
}