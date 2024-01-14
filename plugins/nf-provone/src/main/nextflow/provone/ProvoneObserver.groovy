/*
 * Copyright 2021, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.provone

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import nextflow.Session
import nextflow.trace.TraceObserver

import org.openprovenance.prov.model.Document
import org.openprovenance.prov.interop.InteropFramework
import org.openprovenance.prov.interop.GenericInteropFramework
import org.provtools.provone.model.ProvOneNamespace
import org.provtools.provone.vanilla.ProvOneFactory

/**
 * Example workflow events observer
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class ProvoneObserver implements TraceObserver {

    ProvOneFactory pFactory = new ProvOneFactory();
    ProvOneNamespace ns = new ProvOneNamespace();
    Document document = pFactory.newDocument()

    @Override
    void onFlowCreate(Session session) {
        log.info "Pipeline is starting! 🚀"

        // Configure namespaces used in the document
        this.ns.addKnownNamespaces();
        this.ns.register("exa", "http://example.com/");
        this.ns.register("dcterms", "http://purl.org/dc/terms/");
        this.ns.register("schema", "https://schema.org/");
        this.ns.register("foaf", "http://xmlns.com/foaf/0.1/");
        this.ns.register("scoro", "http://purl.org/spar/scoro/");

        //// Create the document and set the namespaces
        document.setNamespace(this.ns);
    }

    @Override
    void onFlowComplete() {
        log.info "Pipeline complete! 👋"
        log.info "Serialize provenance document ..."

        String filename = "provone_provenance.jsonld";
        InteropFramework intF = new GenericInteropFramework(this.pFactory);

        intF.writeDocument(filename, document);     
    }
}
