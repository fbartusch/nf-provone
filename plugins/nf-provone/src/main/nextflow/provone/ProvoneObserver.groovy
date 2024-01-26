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

import groovy.util.logging.Slf4j
import groovy.transform.CompileStatic
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import nextflow.config.ConfigMap
import nextflow.processor.TaskHandler
import nextflow.processor.TaskProcessor

import nextflow.Session
import nextflow.processor.TaskRun
import nextflow.script.BodyDef
import nextflow.script.ProcessConfig
import nextflow.trace.TraceObserver
import nextflow.trace.TraceRecord
import org.openprovenance.prov.model.Attribute
import org.openprovenance.prov.model.Document
import org.openprovenance.prov.interop.InteropFramework
import org.openprovenance.prov.interop.GenericInteropFramework
import org.openprovenance.prov.model.QualifiedName
import org.openprovenance.prov.model.WasAssociatedWith
import org.openprovenance.prov.model.WasInformedBy
import org.provtools.provone.model.ProvOneNamespace
import org.provtools.provone.model.WasPartOf
import org.provtools.provone.vanilla.Controller
import org.provtools.provone.vanilla.Execution
import org.provtools.provone.vanilla.Program
import org.provtools.provone.vanilla.ProvOneFactory

import java.nio.file.Path
import org.provtools.provone.vanilla.User
import org.provtools.provone.vanilla.Workflow

/**
 * Workflow events observer that creates
 * provenance metadata during execution
 *
 * @author Felix Bartusch <felix.bartusch@uni-tuebingen.de>
 */
@Slf4j
@CompileStatic
class ProvOneObserver implements TraceObserver {

    Session sessionSave = null

    ProvOneFactory pFactory = new ProvOneFactory();
    ProvOneNamespace ns = new ProvOneNamespace();
    Document document = pFactory.newDocument()

    // PROV and ProvONE elements
    Workflow workflow = null
    Execution workflowExecution = null
    User user = null

    /**
     * The is method is invoked when the flow is going to start
     */
    @Override
    void onFlowCreate(Session session) {
        log.info "onFlowCreate called."
        log.info(session.config.toString());
        log.info(session.dump());

        sessionSave = session

        log.info "\tSet namespaces ..."

        // Configure namespaces used in the document
        this.ns.addKnownNamespaces();
        this.ns.register("exa", "http://example.com/");
        this.ns.register("dcterms", "http://purl.org/dc/terms/");
        this.ns.register("schema", "https://schema.org/");
        this.ns.register("foaf", "http://xmlns.com/foaf/0.1/");
        this.ns.register("scoro", "http://purl.org/spar/scoro/");
        document.setNamespace(ns)

        // Read user information file
        String userINIPath = null
        if(session.config.containsKey("provone")) {
            ConfigMap provoneConfig = session.config["provone"]
            if(provoneConfig.containsKey("userINI")) {
                userINIPath = Path.of((String) provoneConfig["userINI"]);

                // Resolve $projectDir
                if(userINIPath.startsWith('$projectDir')) {
                    userINIPath = userINIPath.replace('$projectDir', session.getWorkflowMetadata().getProjectDir().toString())
                }
            }
        }
        // Use default path in user's home
        if(userINIPath == null)
            userINIPath = session.workflowMetadata.homeDir.toString() + "/.provone/user.ini";

        // Create ProvOne user element
        Path p = Path.of(userINIPath);
        if(p.exists() && !p.isDirectory()) {
            user = pFactory.newUser(p, "https://example.com/", "exa")
            document.getStatementOrBundle().add(user)
        } else {
            log.info("User INI file does not exist: " + userINIPath)
        }

        // Create a new ProvOne workflow object and add it to the document
        workflow = pFactory.newWorkflow(session.workflowMetadata.getScriptId(),
                    session.workflowMetadata.getScriptName(),
                    session.workflowMetadata.getScriptFile().toString(),
                    session.workflowMetadata.getRepository(),
                    session.workflowMetadata.getCommitId(),
                    session.workflowMetadata.getRevision());
        document.getStatementOrBundle().add(workflow)

        // Execution metadata
        QualifiedName exeQN = pFactory.newQualifiedName("https://example.com/", session.workflowMetadata.getSessionId().toString(), "ex");
        Collection<Attribute> wfAttrs = new LinkedList<>();
        // Add projectDir as attribute
        wfAttrs.add(pFactory.newAttribute("https://example.com/", "projectDir", "ex",
                session.workflowMetadata.getProjectDir(), pFactory.getName().XSD_STRING));
        // Add launchDir as attribute
        wfAttrs.add(pFactory.newAttribute("https://example.com/", "launchDir", "ex",
                session.workflowMetadata.getLaunchDir(), pFactory.getName().XSD_STRING));
        // Add workDir as attribute
        wfAttrs.add(pFactory.newAttribute("https://example.com/", "workDir", "ex",
                session.workflowMetadata.getWorkDir(), pFactory.getName().XSD_STRING));
        // Add label
        wfAttrs.add(pFactory.newAttribute(Attribute.AttributeKind.PROV_LABEL,
                pFactory.newInternationalizedString(session.workflowMetadata.getRunName()),
                pFactory.getName().XSD_STRING));

        workflowExecution = pFactory.newExecution(exeQN, session.workflowMetadata.getStart(), null, wfAttrs);
        document.getStatementOrBundle().add(workflowExecution)

        // Associate user with workflow execution and plan (e.g. the Nextflow script)
        QualifiedName userWFAssocQN = pFactory.newQualifiedName("https://example.com/",
                user.getId().toString() + "_" + workflow.getId().toString(), "ex");
        WasAssociatedWith userWFAssoc = pFactory.newWasAssociatedWith(userWFAssocQN, workflowExecution.getId(), user.getId(), workflow.getId(), null);
        document.getStatementOrBundle().add(userWFAssoc)

        // Create Controller Provone Element
        QualifiedName controllerQN = pFactory.newQualifiedName("https://example.com/", "Nextflow", "ex");
        Collection<Attribute> controllerAttrs = new LinkedList<>();
        controllerAttrs.add(pFactory.newAttribute(Attribute.AttributeKind.PROV_LABEL, pFactory.newInternationalizedString("Nextflow"),
                pFactory.getName().XSD_STRING));
        // Add Build and timestamp as attribute
        controllerAttrs.add(pFactory.newAttribute("https://example.com/", "build", "ex",
                session.workflowMetadata.getNextflow().getBuild().toString(), pFactory.getName().XSD_STRING));
        controllerAttrs.add(pFactory.newAttribute("https://example.com/", "timestamp", "ex",
                session.workflowMetadata.getNextflow().getTimestamp(), pFactory.getName().XSD_STRING));
        controllerAttrs.add(pFactory.newAttribute("https://schema.org/", "softwareVersion", "schema",
                session.workflowMetadata.getNextflow().getVersion().toString(),
                pFactory.newQualifiedName("https://schema.org/", "Text", "schema")));
        Controller wfController = pFactory.newController(controllerQN, controllerAttrs)
        document.getStatementOrBundle().add(wfController)
    }

    /**
     * The is method is invoked when the flow is going to start
     */
    @Override
    void onFlowBegin() {
        log.info "onFlowBegin called"
    }

    /**
     * This method is invoked when the flow is going to complete
     */
    @Override
    void onFlowComplete() {
        log.info "onFlowComplete called"
        log.info "\tSerialize provenance document ..."

        // Set Workflow endTime
        workflowExecution.setEndTime(sessionSave.getWorkflowMetadata().getComplete())

        String filename = "provone_provenance.jsonld";
        InteropFramework intF = new GenericInteropFramework(this.pFactory);

        intF.writeDocument(filename, document);     
    }

    /*
     * Invoked when the process is created.
     */
    @Override
    void onProcessCreate( TaskProcessor process ){
        log.info "onProcessCreate called"
        log.info(process.dump());

        // Process has a name (e.g. "INDEX")
        process.getName()
        process.getId()

    }

    /*
     * Invoked when all tak have been executed and process ends.
     */
    @Override
    void onProcessTerminate( TaskProcessor process ){
        log.info "onProcessTerminate called"
        log.info(process.dump());

        // Process Environment has zero size ...
        Map<String, String> processEnvironment = process.getProcessEnvironment()

        ProcessConfig processConfig = process.getConfig()
        BodyDef taskBody = process.getTaskBody()

        process.getId()
    }

    /**
     * This method when a new task is created and submitted in the nextflow
     * internal queue of pending task to be scheduled to the underlying
     * execution backend
     *
     * @param handler
     * @param trace
     */
    @Override
    void onProcessPending(TaskHandler handler, TraceRecord trace){
        log.info "onProcessPending called"
        log.info(handler.dump());
        log.info(trace.dump());
    }

    /**
     * This method is invoked before a process run is going to be submitted
     *
     * @param handler
     *      The {@link TaskHandler} instance for the current task.
     * @param trace
     *      The associated {@link TraceRecord} for the current task.
     */
    @Override
    void onProcessSubmit(TaskHandler handler, TraceRecord trace){
        log.info "onProcessSubmit called"
        log.info(handler.dump());
        log.info(trace.dump());
    }

    /**
     * This method is invoked when a process run is going to start
     *
     * @param handler
     *      The {@link TaskHandler} instance for the current task.
     * @param trace
     *      The associated {@link TraceRecord} for the current task.
     */
    @Override
    void onProcessStart(TaskHandler handler, TraceRecord trace){
        log.info "onProcessStart called"
        log.info(handler.dump());
        log.info(trace.dump());
    }

    /**
     * This method is invoked when a process run completes
     *
     * @param handler
     *      The {@link TaskHandler} instance for the current task.
     * @param trace
     *      The associated {@link TraceRecord} for the current task.
     */
    @Override
    void onProcessComplete(TaskHandler handler, TraceRecord trace){
        log.info "onProcessComplete called"
        log.info(handler.dump());
        log.info(trace.dump());

        // Execution
        QualifiedName exeQN = pFactory.newQualifiedName("https://example.com/", handler.getTask().getHash().toString(), "ex")
        OffsetDateTime startTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(handler.getStartTimeMillis()), ZoneId.systemDefault());
        OffsetDateTime endTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(handler.getCompleteTimeMillis()), ZoneId.systemDefault());
        Execution processExe = pFactory.newExecution(exeQN, startTime, endTime, handler.getTask().getProcessor().getName())
        document.getStatementOrBundle().add(processExe)

        // Program
        // The qualified name will be computed from the executed script. There is no hash for the script itself.
        QualifiedName progQN = pFactory.newQualifiedName("https://example.com/", handler.getTask().getScript().hashCode().toString(), "ex")
        // Attributes
        Collection<Attribute> progAttrs = new LinkedList<>();
        progAttrs.add(pFactory.newAttribute(Attribute.AttributeKind.PROV_LABEL,
                pFactory.newInternationalizedString(handler.getTask().getProcessor().getName()),
                pFactory.getName().XSD_STRING));
        // Add script as attribute
        progAttrs.add(pFactory.newAttribute("https://example.com/", "script", "ex",
                handler.getTask().getScript().toString().trim(), pFactory.getName().XSD_STRING));
        Program processProg = pFactory.newProgram(progQN, progAttrs)
        document.getStatementOrBundle().add(processProg)

        // Associate Execution with the Program and User
        QualifiedName userProcAssocQN = pFactory.newQualifiedName("https://example.com/",
                user.getId().toString() + "_" + processExe.getId().toString(), "ex");
        WasAssociatedWith userProcessAssoc = pFactory.newWasAssociatedWith(userProcAssocQN, processExe.getId(), user.getId(), processProg.getId(), null);
        document.getStatementOrBundle().add(userProcessAssoc)

        // Process execution is part of workflow execution
        WasPartOf exePartOfWF = pFactory.newWasPartOf(exeQN, workflowExecution.getId())
        document.getStatementOrBundle().add(exePartOfWF)

        // Program is part of workflow
        WasPartOf progPartOfWF = pFactory.newWasPartOf(progQN, workflow.getId())
        document.getStatementOrBundle().add(progPartOfWF)

        // TODO wasInformedBy
        // prov:wasInformedBy is adopted in ProvONE to state that an Execution communicates with another
        // Execution through an output-input relation, and thereby triggers its execution.
    }

    /**
     * method invoked when a task execution is skipped because the result is cached (already computed)
     * or stored (due to the usage of `storeDir` directive)
     *
     * @param handler
     *      The {@link TaskHandler} instance for the current task
     * @param trace
     *      The trace record for the cached trace. When this event is invoked for a store task
     *      the {@code trace} record is expected to be {@code null}
     */
    @Override
    void onProcessCached(TaskHandler handler, TraceRecord trace){
        log.info "onProcessCached called"
        log.info(handler.dump());
        log.info(trace.dump());
    }

    /**
     * @return {@code true} whenever this observer requires to collect task execution metrics
     */
    @Override
    boolean enableMetrics(){ false }

    /**
     * Method that is invoked, when a workflow fails.
     *
     * @param handler
     *      The {@link TaskHandler} instance for the current task.
     * @param trace
     *      The associated {@link TraceRecord} for the current task.
     */
    @Override
    void onFlowError(TaskHandler handler, TraceRecord trace){
        log.info "onFlowError called"
        log.info(handler.dump());
        log.info(trace.dump());
    }

    /**
     * Method that is invoke when an output file is published
     * into a `publishDir` folder.
     *
     * @param destination
     *      The destination path at `publishDir` folder.
     */
    @Override
    void onFilePublish(Path destination){
        log.info "onFilePublish called"
        log.info(Path.dump());
    }

    /**
     * Method that is invoke when an output file is published
     * into a `publishDir` folder.
     *
     * @param destination
     *      The destination path at `publishDir` folder.
     * @param source
     *      The source path at `workDir` folder.
     */
    @Override
    void onFilePublish(Path destination, Path source){
        onFilePublish(destination)
    }
}
