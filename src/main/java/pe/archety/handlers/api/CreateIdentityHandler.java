package pe.archety.handlers.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.apache.commons.validator.routines.EmailValidator;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;
import pe.archety.*;


import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.logging.Logger;

public class CreateIdentityHandler implements HttpHandler {
    private static final Logger logger = Logger.getLogger(CreateIdentityHandler.class.getName());
    private static GraphDatabaseService graphDB;
    private static ObjectMapper objectMapper;
    private static final EmailValidator validator = EmailValidator.getInstance();
    private static final PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
    private static final BatchWriterService batchWriterService = BatchWriterService.INSTANCE;

    public CreateIdentityHandler( GraphDatabaseService graphDB, ObjectMapper objectMapper ) {
        this.graphDB = graphDB;
        this.objectMapper = objectMapper;
    }

    /*
        Input:
        { "email": "me@meh.com" }
        or
        { "phone": "3125137509"}
        { "phone": "3125137509", "region": "US"}
     */

    @Override
    public void handleRequest( final HttpServerExchange exchange ) throws Exception {
        exchange.getResponseHeaders().put( Headers.CONTENT_TYPE, ArchetypeServer.JSON_UTF8 );
        exchange.startBlocking();
        final InputStream inputStream = exchange.getInputStream();
        final String body = new String(ByteStreams.toByteArray(inputStream), Charsets.UTF_8);
        HashMap input = objectMapper.readValue(body, HashMap.class);

        boolean validIdentity = false;
        String identity = "";

        if( input.containsKey( "email" ) ){
            String email = (String)input.get( "email" );
            if( validator.isValid( email ) ){
                validIdentity = true;
                identity = email;
            }
        } else if( input.containsKey( "phone" ) ){
            Phonenumber.PhoneNumber phoneNumber;
            try {
                String phone = (String) input.get("phone");
                if (input.containsKey("region")) {
                    String region = (String) input.get("region");

                    phoneNumber = phoneUtil.parse(phone, region);
                } else {
                    phoneNumber = phoneUtil.parse(phone, "US");
                }
            } catch (NumberParseException e) {
                String error = "Error Parsing Phone Number: " + body;
                logger.severe(error);
                exchange.getResponseSender().send("{\"error\":\"" + error + "\"}");
                return;
            }

            if( phoneUtil.isValidNumber( phoneNumber ) ) {
                validIdentity = true;
                identity = phoneUtil.format( phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164 ); // "+41446681800"
            } else {
                String error = "Parameters email or phone required.";
                exchange.getResponseSender().send("{\"error\":\"" + error + "\"}");
                return;
            }
        }

        String identityHash = "";
        if( validIdentity ) {
            identityHash = ArchetypeConstants.calculateHash(identity);
            Long identityNodeId = ArchetypeServer.identityCache.getIfPresent(identityHash);

            if( identityNodeId == null ) try (Transaction tx = graphDB.beginTx()) {

                // If the node id is not in the cache, let's try to find the node in the index.
                ResourceIterator<Node> results = graphDB.findNodesByLabelAndProperty(Labels.Identity, "identity", identityHash).iterator();

                // If it's in the index, cache it
                if (results.hasNext()) {
                    Node identityNode = results.next();
                    ArchetypeServer.identityCache.put(identityHash, identityNode.getId());
                } else {
                    // If it's not in the index go create it asynchronously
                    HashMap<String, Object> write = new HashMap<>();
                    HashMap<String, Object> data = new HashMap<>();
                    data.put("identityHash", identityHash);
                    write.put(ArchetypeConstants.ACTION, BatchWriterServiceAction.CREATE_IDENTITY);
                    write.put(ArchetypeConstants.DATA, data);
                    batchWriterService.queue.put(write);
                }
            }
        }

        exchange.setResponseCode(201);
        exchange.getResponseSender().send(ByteBuffer.wrap(
                objectMapper.writeValueAsBytes(
                        Collections.singletonMap("identity", identity))));
    }

}