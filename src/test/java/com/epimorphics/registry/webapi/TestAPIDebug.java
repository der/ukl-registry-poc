/******************************************************************
 * File:        TestAPIDebug.java
 * Created by:  Dave Reynolds
 * Created on:  6 Mar 2013
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

import static org.junit.Assert.*;

import java.io.IOException;

import org.junit.Test;

import com.epimorphics.registry.util.Prefixes;
import com.epimorphics.registry.vocab.RegistryVocab;
import com.epimorphics.registry.vocab.Version;
import com.epimorphics.server.core.ServiceConfig;
import com.epimorphics.server.core.Store;
import com.epimorphics.vocabs.SKOS;
import com.hp.hpl.jena.query.Dataset;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.NodeIterator;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.sparql.util.Closure;
import com.hp.hpl.jena.vocabulary.RDF;
import com.sun.jersey.api.client.ClientResponse;

/**
 * Place where new webapi tests can be developed to investigate
 * reported problems. Once running they get merged
 * in the main tests in TestAPI.
 *
 * @author <a href="mailto:dave@epimorphics.com">Dave Reynolds</a>
 */
public class TestAPIDebug extends TomcatTestBase {

    static final String EXT_BLACK = "http://example.com/colours/black";
    static final String REG1 = BASE_URL + "reg1";
    static final String REG1_URI = ROOT_REGISTER + "reg1";

    String getWebappRoot() {
        return "src/test/webapp";
    }

    @Test
    public void testDebug() throws IOException {
        // Set up some base data
//        assertEquals(201, postFileStatus("test/reg1.ttl", BASE_URL));
//        assertEquals(201, postFileStatus("test/red.ttl", REG1));

        String stt_reg = BASE_URL + "system/typed-templates";
        assertEquals(201, postFileStatus("test/typed-templates-reg.ttl", BASE_URL + "system"));
        assertEquals(201, postFileStatus("test/concept-scheme-typed-template.ttl", stt_reg));
        assertEquals(204, post(stt_reg + "/_concept-scheme?update&status=stable").getStatus());

        LibReg reg = new LibReg();
        Resource test = ModelFactory.createDefaultModel().createResource("http://example.com/test")
                .addProperty(RDF.type, SKOS.ConceptScheme)
                .addProperty(RDF.type, SKOS.Collection);
        assertEquals("concept-scheme-render.vm", reg.templateFor(test) );
        
        assertEquals(201, postFileStatus("test/collection-typed-template.ttl", stt_reg));
        assertEquals(204, post(stt_reg + "/_collection?update&status=stable").getStatus());
        assertEquals("collection-render.vm", reg.templateFor(test) );
    }

    // Debugging utility only, should not be used while transactions are live
    public void printResourceState(String...uris) {
        Store storesvc = ServiceConfig.get().getServiceAs("basestore", Store.class);
        storesvc.lock();
        try {
            Dataset ds =  storesvc.asDataset();
            Model store = ds.getDefaultModel();
            Model description = ModelFactory.createDefaultModel();
            for (String uri: uris) {
                Resource r = store.getResource(uri);
                Closure.closure(r, false, description);
                if (r.hasProperty(Version.currentVersion)) {
                    r = r.getPropertyResourceValue(Version.currentVersion);
                    Closure.closure(r, false, description);
                }
            }
            description.setNsPrefixes( Prefixes.get() );
            description.write(System.out, "Turtle");
            for (NodeIterator ni = description.listObjectsOfProperty(RegistryVocab.sourceGraph); ni.hasNext(); ) {
                String graphname = ni.next().asResource().getURI();
                Model graph = ModelFactory.createDefaultModel().add( ds.getNamedModel(graphname) );
                System.out.println("Graph " + graphname);
                graph.setNsPrefixes(Prefixes.get());
                graph.write(System.out, "Turtle");
            }
        } finally {
            storesvc.unlock();
        }
    }

    public static void debugStatus(ClientResponse response) {
        if (response.getStatus() >= 400) {
            System.out.println("Response was: " + response.getEntity(String.class) + " (" + response.getStatus() + ")");
            assertTrue(false);
        }
    }
}
