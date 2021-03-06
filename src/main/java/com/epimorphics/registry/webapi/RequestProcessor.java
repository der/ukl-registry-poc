/******************************************************************


 * File:        RequestProcessor.java
 * Created by:  Dave Reynolds
 * Created on:  21 Jan 2013
 *
 * (c) Copyright 2013, Epimorphics Limited
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *****************************************************************/

package com.epimorphics.registry.webapi;

import static com.epimorphics.webapi.marshalling.RDFXMLMarshaller.FULL_MIME_RDFXML;
import static com.epimorphics.webapi.marshalling.RDFXMLMarshaller.MIME_RDFXML;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.UnavailableSecurityManagerException;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.epimorphics.registry.commands.CommandUpdate;
import com.epimorphics.registry.core.Command;
import com.epimorphics.registry.core.Command.Operation;
import com.epimorphics.registry.core.ForwardingRecord;
import com.epimorphics.registry.core.ForwardingRecord.Type;
import com.epimorphics.registry.core.ForwardingService;
import com.epimorphics.registry.core.MatchResult;
import com.epimorphics.registry.core.Registry;
import com.epimorphics.registry.security.UserInfo;
import com.epimorphics.registry.util.JSONLDSupport;
import com.epimorphics.registry.util.PATCH;
import com.epimorphics.registry.util.UiForm;
import com.epimorphics.server.core.ServiceConfig;
import com.epimorphics.server.templates.VelocityRender;
import com.epimorphics.server.webapi.BaseEndpoint;
import com.epimorphics.server.webapi.WebApiException;
import com.epimorphics.util.NameUtils;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.util.FileManager;
import com.hp.hpl.jena.util.FileUtils;
import com.sun.jersey.api.NotFoundException;
import com.sun.jersey.multipart.FormDataBodyPart;
import com.sun.jersey.multipart.FormDataMultiPart;
import com.sun.jersey.multipart.FormDataParam;

/**
 * Filter all requests as possible register API requests.
 * @author <a href="mailto:dave@epimorphics.com">Dave Reynolds</a>
 */
@Path("{path: .*}")
public class RequestProcessor extends BaseEndpoint {
    static final Logger log = LoggerFactory.getLogger( RequestProcessor.class );

    public static final String FULL_MIME_TURTLE = "text/turtle; charset=UTF-8";
    public static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    public static final String UI_PATH = "ui";
    private static final String SYSTEM_QUERY = "system/query";

    @GET
    @Produces("text/html")
    public Response htmlrender() {
        String path = uriInfo.getPath();
        if (path.startsWith(UI_PATH) ) {
            String template = path.substring(UI_PATH.length()+1) + ".vm";
            try {
                return render(template, uriInfo, context, request);
            } catch (ResourceNotFoundException e) {
                // Will chain through to file serving
                throw new NotFoundException();
            }
        } else if (path.startsWith(SYSTEM_QUERY) || path.equals("favicon.ico")) {
            // Pass to the generic velocity handler, which in turn falls through to file serving
            throw new NotFoundException();
        }

        PassThroughResult result = checkForPassThrough();
        if (result != null && result.isDone()) {
            return result.getResponse();
        }
        MultivaluedMap<String, String> parameters = uriInfo.getQueryParameters();
        if (parameters.containsKey(Parameters.FORMAT)) {
            String mime = MIME_RDFXML;
            String format = parameters.getFirst(Parameters.FORMAT);
            if (format.equals("ttl")) {
                mime = FULL_MIME_TURTLE;
            } else if (format.equals("jsonld")) {
                mime = JSONLDSupport.FULL_MIME_JSONLD;
            }
            return readAsRDF(result, mime);
        } if (parameters.containsKey(Parameters.ANNOTATION)) {
            return readAsRDF(result, FULL_MIME_TURTLE);
        } else {
            return render("main.vm", uriInfo, context, request);
        }
    }

    private Response readAsRDF(PassThroughResult ptr, String mime) {
        Response response = doRead(ptr);
        return Response.ok().type(mime).entity(response.getEntity()).build();
    }

    public static Response render(String template, UriInfo uriInfo, ServletContext context, HttpServletRequest request, Object...params) {
        String language = request.getLocale().getLanguage();
        if (language.isEmpty()) {
            language = "en";
        }
        Object[] fullParams = new Object[params.length + 10];
        int i = 0;
        while (i < params.length) {
            fullParams[i] = params[i];
            i++;
        }
        fullParams[i++] = "registry";
        fullParams[i++] = Registry.get();
        fullParams[i++] = "subject";
        fullParams[i++] = SecurityUtils.getSubject();
        fullParams[i++] = "requestor";
        fullParams[i++] = getRequestor(request);
        fullParams[i++] = "request";
        fullParams[i++] = request;
        fullParams[i++] = "language";
        fullParams[i++] = language;

        VelocityRender velocity = ServiceConfig.get().getServiceAs(Registry.VELOCITY_SERVICE, VelocityRender.class);
        StreamingOutput out = velocity.render(template, uriInfo.getPath(), context, uriInfo.getQueryParameters(), fullParams);
        return Response.ok().type("text/html").entity(out).build();
    }

    public static String getRequestor(HttpServletRequest request) {
        try {
            UserInfo user = (UserInfo) SecurityUtils.getSubject().getPrincipal();
            if (user != null) {
                return NameUtils.encodeSafeName(user.toString());
            }
        } catch (UnavailableSecurityManagerException e) {
            // shiro not running, presumably test mode, fall through to default requestor based on IP
        }

        if (request.getHeader(FORWARDED_FOR_HEADER) != null) {
            return request.getHeader(FORWARDED_FOR_HEADER);
        }

        return request.getRemoteAddr();
    }

    @GET
    @Produces({FULL_MIME_TURTLE, FULL_MIME_RDFXML, JSONLDSupport.FULL_MIME_JSONLD})
    public Response read() {
        PassThroughResult result = checkForPassThrough();
        if (result != null && result.isDone()) {
            return result.getResponse();
        } else {
            return doRead(result);
        }
    }

    @GET
    public Response defaultRead() {
        // Will chain through to file serving
        throw new NotFoundException();
    }


    private Response doRead(PassThroughResult ptr) {
        String path = uriInfo.getPath();
        if (path.startsWith(SYSTEM_QUERY) || path.startsWith(UI_PATH) ) {
            // Will chain through to file serving/fuseki
            throw new NotFoundException();
        }

        Command command = null;
        if (uriInfo.getQueryParameters().containsKey(Parameters.QUERY)) {
            command = makeCommand( Operation.Search );
        } else {
            command = makeCommand( Operation.Read );
            if (ptr != null) {
                command.setDelegation(ptr.getRecord());
            }
        }
        return command.execute();
    }

    private PassThroughResult checkForPassThrough() {
        String path = uriInfo.getPath();
        ForwardingService fs = Registry.get().getForwarder();
        if (fs != null) {
            MatchResult match = fs.match(path);
            if (match != null) {
                ForwardingRecord fr = match.getRecord();
                PassThroughResult result = new PassThroughResult(fr);
                if (fr.getType() == Type.DELEGATE) {
                    // Delegation should bubble up to the read command
                    return result;
                }
                String forwardTo = fr.getTarget();
                String remainder = match.getPathRemainder();
                if (!remainder.isEmpty()) {
                    if (!forwardTo.endsWith("/")) {
                        forwardTo += "/";
                    }
                    forwardTo += remainder;
                }
                URI location = null;
                try {
                    location = new URI(forwardTo);
                } catch (Exception e) {
                    throw new WebApiException(Response.Status.INTERNAL_SERVER_ERROR, "Illegal URI registered at " + fr.getLocation() + " - " + fr.getTarget());
                }
                result.setResponse( Response.ok().status(fr.getForwardingCode()).location(location).build() );
                return result;
            }
        }
        return null;
    }

    private Command makeCommand(Operation op) {
        Command c = Registry.get().make(op, uriInfo.getPath(), uriInfo.getQueryParameters());
        c.setRequestor(getRequestor(request));
        return c;
    }

    @POST
    @Consumes({"text/plain"})
    public Response validate(@Context HttpHeaders hh, InputStream body) {
        MultivaluedMap<String, String> parameters = uriInfo.getQueryParameters();
        if ( parameters.get(Parameters.VALIDATE) != null ) {
            if (body != null) {
                for (String uri : FileManager.get().readWholeFileAsUTF8(body).split("\\s")) {
                    parameters.add(Parameters.VALIDATE, uri);
                }
            }
            Command command = makeCommand(Operation.Validate);
            return command.execute();
        } else {
            throw new WebApiException(Response.Status.BAD_REQUEST, "No operations supported on text/plain other than validate");
        }
    }

    @POST
    @Consumes({MIME_TURTLE, MIME_RDFXML, JSONLDSupport.MIME_JSONLD})
    public Response register(@Context HttpHeaders hh, InputStream body) {
        MultivaluedMap<String, String> parameters = uriInfo.getQueryParameters();
        Command command = null;
        if ( parameters.get(Parameters.VALIDATE) != null ) {
            return validate(hh , body);
        } else if ( parameters.get(Parameters.TAG) != null ) {
            // TODO to support tagging delegated regsiter would need a checkForPassThrough here
            command = makeCommand(Operation.Tag);
        } else if ( parameters.get(Parameters.STATUS_UPDATE) != null ) {
            command = makeCommand(Operation.StatusUpdate);
        } else if (body != null) {
            command = makeCommand(Operation.Register);
            setPayload(command, hh, body, true);
        } else {
            throw new WebApiException(Status.BAD_REQUEST, "Did not recognize request");
        }
        return command.execute();
    }

    private void setPayload(Command command, HttpHeaders hh, InputStream body, boolean isPOST) {
        try {
            Model model = getBodyModel(hh, body, isPOST);
            if (model == null) {
                throw new WebApiException(Status.BAD_REQUEST, "Did not recognize request");
            }
            command.setPayload( model );
        } catch(Exception e) {
            throw new WebApiException(Response.Status.BAD_REQUEST, "Payload failed to parse: " + e.getMessage());
        }
    }

    @PUT
    @Consumes({MIME_TURTLE, MIME_RDFXML, JSONLDSupport.MIME_JSONLD})
    public Response update(@Context HttpHeaders hh, InputStream body) {
        MultivaluedMap<String, String> parameters = uriInfo.getQueryParameters();
        Command command = null;
        if ( parameters.get(Parameters.GRAPH) != null ) {
            command = makeCommand( Operation.GraphRegister );
            setPayload(command, hh, body, true);
        } else if ( parameters.get(Parameters.ANNOTATION) != null ) {
            command = makeCommand( Operation.Annotate );
            setPayload(command, hh, body, true);
        } else {
            command = makeCommand( Operation.Update );
            setPayload(command, hh, body, false);
        }
        return command.execute();
    }

    @DELETE
    public Object delete() {
        Command command = makeCommand( Operation.Delete );
        return command.execute();
    }

    @PATCH
    @Consumes({MIME_TURTLE, MIME_RDFXML, JSONLDSupport.MIME_JSONLD})
    public Response updatePatch(@Context HttpHeaders hh, InputStream body) {
        Command command = makeCommand( Operation.Update );
        ((CommandUpdate)command).setToPatch();
        setPayload(command, hh, body, false);
        return command.execute();
    }

    @POST
    @Consumes({MediaType.APPLICATION_FORM_URLENCODED})
    public Response simpleForm(@Context HttpHeaders hh, MultivaluedMap<String, String> form) {
        String action = form.getFirst("action");
        if ("register-inline".equals(action)) {
            String target = uriInfo.getPath();
            target = Registry.get().getBaseURI() + (target.isEmpty() ? "/" : "/" + target + "/");
            Resource r = UiForm.create(form, target);

//            // TEMP debug
//            System.out.println("Form created resource:");
//            r.getModel().setNsPrefixes(Prefixes.get());
//            r.getModel().write(System.out, "Turtle");

            Command command = makeCommand( Operation.Register );
            command.setPayload( r.getModel() );
            return command.execute();
        } else {
            // Default is to invoke register, e.g. for status update processing
            return register(hh, null);
        }
    }

    @POST
    public Response nullForm(@Context HttpHeaders hh, InputStream body) {
        // Default is to invoke register, e.g. for status update processing
        return register(hh, body);
    }

    @POST
    @Consumes({MediaType.MULTIPART_FORM_DATA})
    public Response fileForm(@Context HttpHeaders hh, FormDataMultiPart multiPart,
//            @FormDataParam("file") InputStream uploadedInputStream,
//            @FormDataParam("file") FormDataContentDisposition fileDetail,
            @FormDataParam("action") String action) {

        List<FormDataBodyPart> fields = multiPart.getFields("file");
        List<String> successfullyProcessed = new ArrayList<>();
        int success = 0;
        int failure = 0;
        StringBuffer errorMessages = new StringBuffer();
        for(FormDataBodyPart field : fields){
            InputStream uploadedInputStream       = field.getValueAs(InputStream.class);
            String filename = field.getContentDisposition().getFileName();
            log.debug("Multipart form invoked on file " + filename + " action=" + action);
            Model payload = ModelFactory.createDefaultModel();
            String base = baseURI(true);
            if (filename.endsWith(".ttl")) {
                payload.read(uploadedInputStream, base, FileUtils.langTurtle);
            } else if (filename.endsWith(".jsonld")) {
                payload = JSONLDSupport.readModel(base, uploadedInputStream);
            } else {
                payload.read(uploadedInputStream, base, FileUtils.langXML);
            }
            Command command = null;
            if (action.equals("register")) {
                command = makeCommand( Operation.Register );
            } else if (action.equals("annotate")) {
                command = makeCommand( Operation.Annotate );
            }
            if (command == null) {
                throw new WebApiException(Response.Status.BAD_REQUEST, "Action " + action + " not recognized");
            }
            command.setPayload(payload);
            try {
                Response response = command.execute();
                if (response.getStatus() >= 400) {
                    failure++;
                    errorMessages.append("<p>" + filename + " - " + response.getEntity() + "</p>");
                } else {
                    success++;
                    successfullyProcessed.add(filename);
                }
            } catch (WebApiException e) {
                failure++;
                log.warn("Error processing uploaded file", e);
                errorMessages.append("<p>" + filename + " - " + e.getResponse().getStatus() + " (" + e.getMessage() + ") </p>");
                throw e;
            } catch (Exception e) {
                failure++;
                log.warn("Error processing uploaded file", e);
                errorMessages.append("<p>" + filename + " - internal error (" + e.getMessage() + ") </p>");
            }
        }
        if (failure != 0) {
            if (success > 0) {
                errorMessages.append("<p>Other file were successfully processed: ");
                for (String succeeded : successfullyProcessed) {
                    errorMessages.append(" " + succeeded);
                }
                errorMessages.append("</p>");
            }
            throw new WebApiException(Response.Status.BAD_REQUEST, errorMessages.toString());
        }
        if (success == 0) {
            throw new WebApiException(Response.Status.BAD_REQUEST, "No file uploaded");
        }
        return Response.ok().build();
    }

    public Model getBodyModel(HttpHeaders hh, InputStream body, boolean isPOST) {
        MediaType mediaType = hh.getMediaType();
        if (mediaType == null) return null;
        String mime = mediaType.getType() + "/" + mediaType.getSubtype();   // ignore parameters

        if (mime.equals(JSONLDSupport.MIME_JSONLD)) {
            return JSONLDSupport.readModel(baseURI(isPOST), body);

        } else {
            String lang = null;
            if ( MIME_RDFXML.equals( mime ) ) {
                lang = FileUtils.langXML;
            } else if ( MIME_TURTLE.equals( mime ) ) {
                lang = FileUtils.langTurtle;
            } else {
                return null;
            }
            Model m = ModelFactory.createDefaultModel();
            m.read(body, baseURI(isPOST), lang);
            return m;
        }
    }

    /**
     * Determine a suitable base URI to use for parsing RDF payloads.
     * For POSTs the base is derived from the URL to which the post was made,
     * for PUT/PATCH it is the parent of the target URL.
     */
    public String baseURI(boolean isPOST) {
        String base = Registry.get().getBaseURI();
        String path = uriInfo.getPath();
        if (!isPOST) {
            if (path.contains("/")) {
                path = NameUtils.splitBeforeLast(path, "/");
            } else {
                path = "";
            }
        }
        if ( ! path.isEmpty()) {
            base += "/" + path;
        }
//        log.debug("Base URI for payload parse: " +  base + "/" );
        return base + "/";
    }


    static class PassThroughResult {
        Response response;
        ForwardingRecord record;

        public PassThroughResult(ForwardingRecord record) {
            this.record = record;
        }

        public boolean isDone() {
            return response != null;
        }

        public Response getResponse() {
            return response;
        }

        public void setResponse(Response response) {
            this.response = response;
        }

        public ForwardingRecord getRecord() {
            return record;
        }

    }
}
