package pe.archety.handlers.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.Undertow;
import io.undertow.server.RoutingHandler;
import org.glassfish.jersey.client.JerseyClient;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.test.TestGraphDatabaseFactory;
import pe.archety.ArchetypeConstants;
import pe.archety.Labels;
import pe.archety.Relationships;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static pe.archety.ArchetypeServer.JSON_UTF8;

public class GetLikesOrHatesHandlerTest {
    private static GraphDatabaseService db;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static Undertow undertow;
    private static final JerseyClient client = JerseyClientBuilder.createClient();

    @Before
    public void setUp() throws JsonProcessingException {
        db = new TestGraphDatabaseFactory().newImpermanentDatabase();
        pupulateDb(db);
        undertow = Undertow.builder()
                .addHttpListener( 9090, "localhost" )
                .setHandler(new RoutingHandler()
                                .add( "GET", "/v1/identities/{identity}/likes", new GetLikesOrHatesHandler(db, objectMapper, Relationships.LIKES))
                                .add( "GET", "/v1/identities/{identity}/hates", new GetLikesOrHatesHandler(db, objectMapper, Relationships.HATES))
                )
                .build();
        undertow.start();

    }

    private void pupulateDb(GraphDatabaseService db) {
        try ( Transaction tx = db.beginTx() ) {
            Node identity1Node = db.createNode(Labels.Identity);
            String identity1 = "maxdemarzi@gmail.com";
            String identity1Hash = ArchetypeConstants.calculateHash(identity1);
            identity1Node.setProperty("identity", identity1Hash);

            Node page1Node = db.createNode(Labels.Page);
            page1Node.setProperty("title", "Neo4j");
            page1Node.setProperty("url", ArchetypeConstants.URLPREFIX + "Neo4j");
            identity1Node.createRelationshipTo(page1Node, Relationships.LIKES);

            Node page2Node = db.createNode(Labels.Page);
            page2Node.setProperty("title", "Mongodb");
            page2Node.setProperty("url", ArchetypeConstants.URLPREFIX + "Mongodb");

            identity1Node.createRelationshipTo(page2Node, Relationships.HATES);

            tx.success();
        }
    }

    @After
    public void tearDown() throws Exception {
        db.shutdown();
        undertow.stop();
    }

    @Test
    public void shouldGetLikesUsingEmail() throws IOException {
        Response response = client.target("http://localhost:9090")
                .register(HashMap.class)
                .path("/v1/identities/" + identity1.get("email") + "/likes")
                .request(JSON_UTF8)
                .get();

        int code = response.getStatus();
        ArrayList<HashMap<String, String>> actual = objectMapper.readValue( response.readEntity( String.class ), ArrayList.class );

        assertEquals( 200, code );
        assertEquals( likes1Response, actual );
    }

    @Test
    public void shouldGetHatesUsingEmail() throws IOException {
        Response response = client.target("http://localhost:9090")
                .register(HashMap.class)
                .path("/v1/identities/" + identity1.get("email") + "/hates")
                .request(JSON_UTF8)
                .get();

        int code = response.getStatus();
        ArrayList<HashMap<String, String>> actual = objectMapper.readValue( response.readEntity( String.class ), ArrayList.class );

        assertEquals( 200, code );
        assertEquals( hates1Response, actual );
    }
    public static final HashMap<String, Object> identity1 =
            new HashMap<String, Object>() {{
                put( "email", "maxdemarzi@gmail.com" );
            }};

    public static final HashMap<String, Object> page1 =
            new HashMap<String, Object>() {{
                put( "url", "http://en.wikipedia.org/wiki/Neo4j" );
            }};

    public static final ArrayList<HashMap<String, String>> likes1Response = new ArrayList<HashMap<String, String>>(){{
        add(            new HashMap<String, String>() {{
            put( "title", "Neo4j");
            put( "url", "http://en.wikipedia.org/wiki/Neo4j" );
        }}
        );
    }};

    public static final ArrayList<HashMap<String, String>> hates1Response = new ArrayList<HashMap<String, String>>(){{
        add(            new HashMap<String, String>() {{
                            put( "title", "Mongodb");
                            put( "url", "http://en.wikipedia.org/wiki/Mongodb" );
                        }}
        );
    }};
}