package pe.archety.handlers.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;
import pe.archety.*;

import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;

import static pe.archety.ArchetypeConstants.URLPREFIX;

public class CreatePageHandler implements HttpHandler {

    private static GraphDatabaseService graphDB;
    private static ObjectMapper objectMapper;

    private static final BatchWriterService batchWriterService = BatchWriterService.INSTANCE;
    private static final CloseableHttpClient httpClient = HttpClients.createDefault();

    public CreatePageHandler( GraphDatabaseService graphDB, ObjectMapper objectMapper ) {
        this.graphDB = graphDB;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handleRequest( final HttpServerExchange exchange ) throws Exception {
        exchange.getResponseHeaders().put( Headers.CONTENT_TYPE, ArchetypeServer.JSON_UTF8 );
        exchange.startBlocking();
        final InputStream inputStream = exchange.getInputStream();
        final String body = new String(ByteStreams.toByteArray(inputStream), Charsets.UTF_8);
        HashMap input = objectMapper.readValue(body, HashMap.class);

        boolean validPage = false;

        String url = "";
        String title = "";
        if( input.containsKey( "url" ) ) {
            url = (String) input.get( "url" );
            if ( !url.startsWith( URLPREFIX ) ) {
                String error = "URL must start with " +  URLPREFIX;
                exchange.getResponseSender().send( "{\"error\":\"" + error + "\"}" );
                return;
            }
        } else if ( input.containsKey( "title" ) ) {
            title = (String) input.get( "title" );
            title = title.replace( " ", "_" );
            title = URLEncoder.encode( title, "UTF-8" );
            url = URLPREFIX + title;
        }

        Long pageNodeId = ArchetypeServer.urlCache.getIfPresent(url);
        if( pageNodeId == null ) try (Transaction tx = graphDB.beginTx()) {

            // If the node id is not in the cache, let's try to find the node in the index.
            ResourceIterator<Node> results = graphDB.findNodesByLabelAndProperty(Labels.Page, "url", url).iterator();

            // If it's in the index, cache it
            if (results.hasNext()) {
                Node pageNode = results.next();
                ArchetypeServer.urlCache.put(url, pageNode.getId());
            } else {
                // Check that it is a valid page
                HttpHead httpHead = new HttpHead(url);
                CloseableHttpResponse response = httpClient.execute(httpHead);
                int code = response.getStatusLine().getStatusCode();
                response.close();

                if (code == 200){
                    if ( title.equals( "" ) ) {
                        title = url.substring( URLPREFIX.length() );
                        title = URLDecoder.decode( title, "UTF-8" );
                        title = title.replace( "_", " " );
                    }

                    // If it's not in the index go create it asynchronously
                    HashMap<String, Object> write = new HashMap<>();
                    HashMap<String, Object> data = new HashMap<>();
                    data.put("url", url);
                    data.put("title", title);
                    write.put(ArchetypeConstants.ACTION, BatchWriterServiceAction.CREATE_PAGE);
                    write.put(ArchetypeConstants.DATA, data);
                    batchWriterService.queue.put(write);
                } else {
                    String error = url + " not found. HTTP Code: " + code;
                    exchange.getResponseSender().send( "{\"error\":\"" + error + "\"}" );
                    return;
                }
            }
        }
        exchange.setResponseCode(201);
        exchange.getResponseSender().send(ByteBuffer.wrap(
                objectMapper.writeValueAsBytes(
                        Collections.singletonMap("url", url))));

    }
}


