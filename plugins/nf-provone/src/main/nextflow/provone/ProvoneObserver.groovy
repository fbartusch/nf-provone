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
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import nextflow.file.FileHolder
import nextflow.processor.TaskHandler
import nextflow.processor.TaskProcessor

import nextflow.Session
import nextflow.script.params.FileInParam
import nextflow.script.params.FileOutParam
import nextflow.trace.TraceObserver
import nextflow.trace.TraceRecord
import nextflow.util.ArrayBag
import org.apache.jena.rdf.model.Model
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.riot.RDFFormat
import org.openprovenance.prov.model.Attribute
import org.openprovenance.prov.model.Document
import org.openprovenance.prov.model.QualifiedName
import org.openprovenance.prov.model.Statement
import org.openprovenance.prov.model.WasAssociatedWith
import org.openprovenance.prov.interop.ApacheJenaInterop
import org.provtools.provone.model.ProvOneNamespace
import org.provtools.provone.model.WasPartOf
import org.provtools.provone.vanilla.Controller
import org.provtools.provone.vanilla.Data
import org.provtools.provone.vanilla.Execution
import org.provtools.provone.vanilla.Program
import org.provtools.provone.vanilla.ProvOneFactory

import java.nio.file.Path
import org.provtools.provone.vanilla.User
import org.provtools.provone.vanilla.Workflow

import org.apache.jena.rdfconnection.RDFConnection

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

    ProvOneFactory pFactory = new ProvOneFactory()
    ProvOneNamespace ns = new ProvOneNamespace()
    Document document = pFactory.newDocument()

    // PROV and ProvONE elements
    Workflow workflow = null
    Execution workflowExecution = null
    User user = null

    // Result provenance file
    String provenanceFile

    // Fuseki connection details
    boolean fusekiUpload = false
    String fusekiURL
    String fusekiUser
    String fusekiPasword

    //TODO In the test example, reflengths.bin two times present in the output
    // Need to keep track for which file we already added as Data element to the document.
    // As files can be output of a process and input to other processes, this would result in multiple similar
    // Data elements in the document

    /**
     * The is method is invoked when the flow is going to start
     */
    @Override
    void onFlowCreate(Session session) {
        // How id debug mode set?
        log.debug "onFlowCreate called."
        log.debug(session.config.toString())
        log.debug(session.dump())

        sessionSave = session

        // Configure namespaces used in the document
        log.debug "\tSet namespaces ..."
        this.ns.addKnownNamespaces()
        this.ns.register("ex", "http://example.com/")
        this.ns.register("dcterms", "http://purl.org/dc/terms/")
        this.ns.register("schema", "https://schema.org/")
        this.ns.register("foaf", "http://xmlns.com/foaf/0.1/")
        this.ns.register("scoro", "http://purl.org/spar/scoro/")
        this.ns.register("github", "https://github.com/")
        document.setNamespace(ns)

        // Parse agent information
        // TODO check for empty config
        log.debug "\tParse configuration ..."
        final provoneParams = session.config.provone as Map
        if (provoneParams.containsKey("agent")) {
            Map agentMap = provoneParams["agent"] as Map
            String title = agentMap.containsKey("title") ? agentMap.get("title") : null
            String givenName = agentMap.containsKey("givenName") ? agentMap.get("givenName") : null
            String familyName = agentMap.containsKey("familyName") ? agentMap.get("familyName") : null
            String mbox = agentMap.containsKey("mbox") ? agentMap.get("mbox") : null
            String orcid = agentMap.containsKey("orcid") ? agentMap.get("orcid") : null
            String label = givenName + " " + familyName
            QualifiedName userQN = pFactory.newQualifiedName("http://example.com/", orcid, "ex")
            user = pFactory.newUser(userQN, label, title, givenName, familyName, mbox, null, null, orcid)
            document.getStatementOrBundle().add(user)
        } else {
            log.debug "Parse agent information: No information found!"
        }

        // Parse Fuseki information
        if (provoneParams.containsKey("fuseki")) {
            Map fusekiMap = provoneParams["fuseki"] as Map
            this.fusekiUpload = fusekiMap.containsKey("upload") ? fusekiMap.get("url") : null

            if (fusekiUpload) {
                this.fusekiURL = fusekiMap.containsKey("url") ? fusekiMap.get("url") : null
                this.fusekiUser = fusekiMap.containsKey("user") ? fusekiMap.get("user") : null
                this.fusekiPasword = fusekiMap.containsKey("password") ? fusekiMap.get("password") : null

                // Everything has to be defined
                if (!(fusekiURL && fusekiUser && fusekiPasword)) {
                    log.debug "Fuseki: at least one of url, user, password is not defined"
                }

                //TODO Check if fuseki talks with us.
            }
        }

        // Parse provenance file name
        this.provenanceFile = provoneParams.containsKey("file") ? provoneParams.get("file") : "provone_provenance.ttl"

        // Workflow

        // TODO Add license information
        // - Check for a file LICENSE: this is in workflow directory for nf-core pipelines

        // TODO Check information stored in nextflow.config under manifest.
        // - demo pipeline example:
        // manifest {
        //    name            = 'nf-core/demo'
        //    author          = """Christopher Hakkaart"""
        //    homePage        = 'https://github.com/nf-core/demo'
        //    description     = """An nf-core demo pipeline"""
        //    mainScript      = 'main.nf'
        //    nextflowVersion = '!>=23.04.0'
        //    version         = '1.0.0'
        //    doi             = ''
        //}

        // TODO Add reliable metadata about the workflow
        // TODO Are there other information sources if there is no manifest?
        // Label should be the pipeline name, e.g. nf-core/demo
        String wfLabel = null
        // A human readable description of the workflow
        String wfDescription = null
        // Workflow author
        String wfAuthor = null
        // Location of the main workflow file (e.g. main.nf for Nextflow)
        String wfLocation = null
        // Repository information and pipeline version
        String wfRepo = null
        String wfCommitID = null
        String wfRevision = null
        String wfVersion = null
        // Nextflow dependency information
        String wfNextflowVersion = null
        String wfDOI = null

        final wfManifest = session.config.manifest as Map
        System.out.println("Manifest: " + wfManifest.toString())
        System.out.println("\nDescprition:\n" + wfManifest.get("description"))
        if (wfManifest) {
            wfLabel  = wfManifest.containsKey("name") ? wfManifest.get("name") : null
            wfDescription  = wfManifest.containsKey("description") ? wfManifest.get("description") : null
            wfAuthor  = wfManifest.containsKey("author") ? wfManifest.get("author") : null
            wfLocation  = session.workflowMetadata.getScriptFile().toString()
            wfRepo  = wfManifest.containsKey("homePage") ? wfManifest.get("homePage") : null
            // For nf-core workflows the version should be also a tag in the pipeline repo
            wfRevision = wfManifest.containsKey("version") ? wfManifest.get("version") : null
            wfVersion = wfManifest.containsKey("version") ? wfManifest.get("version") : null
            wfNextflowVersion = wfManifest.containsKey("nextflowVersion") ? wfManifest.get("nextflowVersion") : null
            System.out.println("label: " + wfLabel)

        }

        // TODO Add additional attributes
        Collection<Attribute> wfAttrs = new LinkedList<>()
        if(wfDescription) {
            wfAttrs.add(pFactory.newAttribute("http://example.com/", "description", "ex",
                    wfDescription, pFactory.getName().XSD_STRING))
        }
        if(wfAuthor) {
            wfAttrs.add(pFactory.newAttribute("http://example.com/", "author", "ex",
                    wfAuthor, pFactory.getName().XSD_STRING))
        }
        if(wfRepo) {
            wfAttrs.add(pFactory.newAttribute("http://example.com/", "repository", "ex",
                    wfRepo, pFactory.getName().XSD_STRING))
        }
        if(wfRevision) {
            wfAttrs.add(pFactory.newAttribute("http://example.com/", "revision", "ex",
                    wfRevision, pFactory.getName().XSD_STRING))
        }
        if(wfNextflowVersion) {
            wfAttrs.add(pFactory.newAttribute("http://example.com/", "nextflowVersion", "ex",
                    wfNextflowVersion, pFactory.getName().XSD_STRING))
        }

        // TODO Add GitHub namespace if workflow has a GitHub repo. -> workflow URI can be accessed.
        // If there repository and revision information this should be unambiguous
        String wfQNLocalName = ""
        if (wfRepo && wfRevision) {
            wfQNLocalName= wfRepo + "-" + wfVersion
        } else {
            wfQNLocalName = session.workflowMetadata.getScriptId()
        }

        // Create a new ProvOne workflow object and add it to the document
        workflow = pFactory.newWorkflow(wfQNLocalName, wfLabel, wfLocation, wfRepo, null, wfRevision)
        workflow.attributes.addAll(wfAttrs)
        document.getStatementOrBundle().add(workflow)

        // Execution
        QualifiedName exeQN = pFactory.newQualifiedName("http://example.com/", session.workflowMetadata.getSessionId().toString(), "ex")
        Collection<Attribute> exeAttrs = new LinkedList<>()
        // Add projectDir as attribute
        exeAttrs.add(pFactory.newAttribute("http://example.com/", "projectDir", "ex",
                session.workflowMetadata.getProjectDir(), pFactory.getName().XSD_STRING))
        // Add launchDir as attribute
        exeAttrs.add(pFactory.newAttribute("http://example.com/", "launchDir", "ex",
                session.workflowMetadata.getLaunchDir(), pFactory.getName().XSD_STRING))
        // Add workDir as attribute
        exeAttrs.add(pFactory.newAttribute("http://example.com/", "workDir", "ex",
                session.workflowMetadata.getWorkDir(), pFactory.getName().XSD_STRING))
        // Add label
        exeAttrs.add(pFactory.newAttribute(Attribute.AttributeKind.PROV_LABEL,
                pFactory.newInternationalizedString(session.workflowMetadata.getRunName()),
                pFactory.getName().XSD_STRING))
        workflowExecution = pFactory.newExecution(exeQN, session.workflowMetadata.getStart(), null, exeAttrs)
        document.getStatementOrBundle().add(workflowExecution)

        // Associate user with workflow execution and plan (e.g. the Nextflow script)
        QualifiedName userWFAssocQN = pFactory.newQualifiedName("http://example.com/",
                user.getId().getLocalPart() + "_" + workflow.getId().getLocalPart(), "ex")
        WasAssociatedWith userWFAssoc = pFactory.newWasAssociatedWith(userWFAssocQN, workflowExecution.getId(), user.getId(), workflow.getId(), null)
        document.getStatementOrBundle().add(userWFAssoc)

        // Create Controller Provone Element
        // TODO Check if session.workflowMetadata.getNextflow() preview and enable contains more interesting properties
        //System.out.println(session.workflowMetadata.getNextflow().toString())
        // nextflow.NextflowMeta(version:24.04.4,
        //                       build:5917, timestamp:01-08-2024 07:05 UTC,
        //                       preview:nextflow.NextflowMeta$Preview@2d9de284,
        //                       enable:nextflow.NextflowMeta$Features@4b5798c2,
        //                       dsl2:true,
        //                       strictModeEnabled:false)#
        String nxfVersion = session.workflowMetadata.getNextflow().getVersion()
        String nxfBuild = session.workflowMetadata.getNextflow().getBuild()
        String nxfTimestamp = session.workflowMetadata.getNextflow().getTimestamp()
        boolean nxfIsDSL2 = session.workflowMetadata.getNextflow().isDsl2()
        String nxfDSL = nxfIsDSL2 ? "2" : "1"

        String qnLocal = "nextflow-" + nxfVersion
        QualifiedName controllerQN = pFactory.newQualifiedName("http://example.com/", qnLocal, "ex")
        Collection<Attribute> controllerAttrs = new LinkedList<>()
        controllerAttrs.add(pFactory.newAttribute(Attribute.AttributeKind.PROV_LABEL, pFactory.newInternationalizedString("Nextflow"),
                pFactory.getName().XSD_STRING))
        // Add Build and timestamp as attribute
        controllerAttrs.add(pFactory.newAttribute("http://example.com/", "build", "ex",
                nxfBuild, pFactory.getName().XSD_STRING))
        controllerAttrs.add(pFactory.newAttribute("http://example.com/", "timestamp", "ex",
                nxfTimestamp, pFactory.getName().XSD_STRING))
        controllerAttrs.add(pFactory.newAttribute("http://example.com/", "dsl", "ex",
                nxfDSL, pFactory.getName().XSD_STRING))
        controllerAttrs.add(pFactory.newAttribute("https://schema.org/", "softwareVersion", "schema",
                nxfVersion.toString(),
                pFactory.newQualifiedName("https://schema.org/", "Text", "schema")))
        Controller wfController = pFactory.newController(controllerQN, controllerAttrs)
        document.getStatementOrBundle().add(wfController)

        // Controller controls the workflow ...
        Statement wfms_controls_wf = pFactory.newControls(controllerQN, workflow.getId())
        document.getStatementOrBundle().add(wfms_controls_wf)
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

        // Write provenance file
        //InteropFramework intF = new GenericInteropFramework(this.pFactory)
        //intF.writeDocument(this.provenanceFile, document)

        // Create Jena model
        ApacheJenaInterop converter = new ApacheJenaInterop(this.pFactory)
        Model m = converter.createJenaModel(document)

        // Write model to file
        try (OutputStream out = new FileOutputStream(this.provenanceFile)) {
            // Write the model in Turtle format using RDFDataMgr
            RDFDataMgr.write(out, m, RDFFormat.TURTLE);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Upload provenance to server
        // Store the provenance of each execution in it's own graph with the it's execution ID in the graph's name
        String graphName = "http://example/trace_" + sessionSave.workflowMetadata.getSessionId().toString()
        if(fusekiUpload) {
            try ( RDFConnection conn = RDFConnection.connectPW(fusekiURL, fusekiUser, fusekiPasword) ) {
                conn.load(graphName, m)
            } catch(Exception e) {
                log.error(e.toString())
            }
        }
    }

    /*
     * Invoked when the process is created.
     */
    @Override
    void onProcessCreate( TaskProcessor process ){
        log.info "onProcessCreate called"
        log.debug(process.dump())

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
        log.debug(process.dump())

        // Process Environment has zero size ...
        //Map<String, String> processEnvironment = process.getProcessEnvironment()

        //ProcessConfig processConfig = process.getConfig()
        //BodyDef taskBody = process.getTaskBody()

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
        log.debug(handler.dump())
        log.debug(trace.dump())
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
        log.debug(handler.dump())
        log.debug(trace.dump())
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
        log.debug(handler.dump())
        log.debug(trace.dump())
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
        log.debug(handler.dump())
        log.debug(trace.dump())

        // Execution
        QualifiedName exeQN = pFactory.newQualifiedName("http://example.com/", handler.getTask().getHash().toString(), "ex")
        OffsetDateTime startTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(handler.getStartTimeMillis()), ZoneId.systemDefault())
        OffsetDateTime endTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(handler.getCompleteTimeMillis()), ZoneId.systemDefault())
        Execution processExe = pFactory.newExecution(exeQN, startTime, endTime, handler.getTask().getProcessor().getName())
        document.getStatementOrBundle().add(processExe)

        // Program
        // The qualified name will be computed from the executed script. There is no hash for the script itself.
        QualifiedName progQN = pFactory.newQualifiedName("http://example.com/", handler.getTask().getScript().hashCode().toString(), "ex")
        // Attributes
        Collection<Attribute> progAttrs = new LinkedList<>()
        progAttrs.add(pFactory.newAttribute(Attribute.AttributeKind.PROV_LABEL,
                pFactory.newInternationalizedString(handler.getTask().getProcessor().getName()),
                pFactory.getName().XSD_STRING))
        // Add script as attribute
        progAttrs.add(pFactory.newAttribute("http://example.com/", "script", "ex",
                handler.getTask().getScript().toString().trim(), pFactory.getName().XSD_STRING))
        Program processProg = pFactory.newProgram(progQN, progAttrs)
        document.getStatementOrBundle().add(processProg)

        // Associate Execution with the Program and User
        QualifiedName userProcAssocQN = pFactory.newQualifiedName("http://example.com/",
                user.getId().getLocalPart() + "_" + processExe.getId().getLocalPart(), "ex")
        WasAssociatedWith userProcessAssoc = pFactory.newWasAssociatedWith(userProcAssocQN, processExe.getId(), user.getId(), processProg.getId(), null)
        document.getStatementOrBundle().add(userProcessAssoc)

        // Process execution is part of workflow execution
        WasPartOf exePartOfWF = pFactory.newWasPartOf(exeQN, workflowExecution.getId())
        document.getStatementOrBundle().add(exePartOfWF)

        // Program is sub program of the workflow
        Statement progSubProgOfWF = pFactory.newHasSubProgram(workflow.getId(), progQN)
        document.getStatementOrBundle().add(progSubProgOfWF)

        //TODO there seems to be a directory present in the output?
        // -> Rework the data generation ...

        // Compile a list of all input files
        // TODO If possible, move this to onProcessStart or somewhere else where we can see the input files
        //  Otherwise output files could be listed as input files, if there are output files in input directories after
        //  the process finished

        // TODO throws java.lang.IndexOutOfBoundsException: Index 0 out of bounds for length 0
        //  [...]
        //  at nextflow.provone.ProvOneObserver.onProcessComplete(ProvoneObserver.groovy:398)
        //	at nextflow.Session.notifyTaskComplete(Session.groovy:1047)
        List<Path> inputFilesList = new LinkedList<Path>()
        Map<FileInParam, Object> fileInParams = handler.getTask().getInputsByType(FileInParam.class)
        for (entry in fileInParams) {

            File fileInParam = null

            // entry.value could be an ArrayBag containing a FileHolder object
            if (entry.value.getClass() == ArrayBag.class) {
                ArrayBag arrayBag = (ArrayBag) entry.value

                // Value stored in the array bag could be a FileHolder object
                if (arrayBag.get(0).class == FileHolder.class) {
                    FileHolder fileHolder = (FileHolder) arrayBag.get(0)
                    fileInParam = new File(fileHolder.getSourceObj().toString())
                }
            }

            if (entry.value.getClass() == FileInParam.class) {
                fileInParam = new File(entry.value.toString())
            }

            // If the parameter is a directory, we have to collect files.
            if (fileInParam.isDirectory()) {
                inputFilesList.addAll(listOfFiles(fileInParam.toPath()))
            }
            if (fileInParam.isFile()) {
                inputFilesList.add(fileInParam.toPath())
            }
        }

        // Create Data elements for each output file
        for (Path inputFile in inputFilesList) {
            Data data = createData(inputFile.toFile())
            //document.getStatementOrBundle().add(data)
            addDataToDocument(data)

            // Current process (ProvONE: execution) used that file
            document.getStatementOrBundle().add(pFactory.newUsed(processExe.id, data.id))
        }


        // Input file(s) of the Execution
        /*
        Map<FileInParam, Object> inputFiles = handler.getTask().getInputsByType(FileInParam.class)
        for (entry in inputFiles) {
            File inputPath = null

            // entry.value could be an ArrayBag containing a FileHolder object
            if (entry.value.getClass() == ArrayBag.class) {
                ArrayBag arrayBag = (ArrayBag) entry.value

                // Value stored in the array bag could be a FileHolder object
                if (arrayBag.get(0).class == FileHolder.class) {
                    FileHolder fileHolder = (FileHolder) arrayBag.get(0)
                    inputPath = new File(fileHolder.getSourceObj().toString())
                }
            }

            if (entry.value.getClass() == FileInParam.class) {
                inputPath = new File(entry.value.toString())
            }

            // Add the file to the document
            if (inputPath.isFile()) {
                Data data = createData(inputPath)
                //document.getStatementOrBundle().add(data)
                addDataToDocument(data)

                // Current process (ProvONE: execution) used that file
                document.getStatementOrBundle().add(pFactory.newUsed(processExe.id, data.id))
            }

            // The path could also be a directory
            if (inputPath.isDirectory()) {
                // Iterate over the directory content
                File[] dirListing = inputPath.listFiles()
                if (dirListing != null) {
                    //TODO check if directory is added here ...

                    for (File child : dirListing) {
                        Data data = createData(child)
                        //document.getStatementOrBundle().add(data)
                        addDataToDocument(data)

                        // Current process (ProvONE: execution) used that file
                        document.getStatementOrBundle().add(pFactory.newUsed(processExe.id, data.id))
                    }
                }
            }
        }*/

        // Compile a list of all output files
        List<Path> outputFilesList = new LinkedList<Path>()
        Map<FileOutParam, Object> fileOutParams = handler.getTask().getOutputsByType(FileOutParam.class)
        for (entry in fileOutParams) {
            File fileOutParam = new File(entry.value.toString())
            // If the parameter is a directory, we have to collect files.
            if (fileOutParam.isDirectory()) {
               outputFilesList.addAll(listOfFiles(fileOutParam.toPath()))
            }
            if (fileOutParam.isFile()) {
                outputFilesList.add(fileOutParam.toPath())
            }
        }

        // Create Data elements for each output file
        for (Path outputFile in outputFilesList) {
            Data data = createData(outputFile.toFile())
            //document.getStatementOrBundle().add(data)
            addDataToDocument(data)

            // Current process (ProvONE: execution) generated that file
            document.getStatementOrBundle().add(pFactory.newWasGeneratedBy(data, null, processExe))
        }

        // TODO wasInformedBy
        // prov:wasInformedBy is adopted in ProvONE to state that an Execution communicates with another
        // Execution through an output-input relation, and thereby triggers its execution.
    }

    private void addDataToDocument(Data data) {
        if ( ! document.getStatementOrBundle().contains(data)) {
            document.getStatementOrBundle().add(data)
        }
    }


    private static List<Path> listOfFiles(Path dirPath) {
        List<Path> result = new ArrayList<Path>()

        Path[] filesList = dirPath.listFiles()
        for(Path file : filesList) {
            if(file.isFile()) {
                result.add(file)
            } else {
                result.addAll(listOfFiles(file))
            }
        }

        return result
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
        log.debug(handler.dump())
        log.debug(trace.dump())
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
        log.debug(handler.dump())
        log.debug(trace.dump())
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
        log.debug(Path.dump())
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

    static getSha1sum(File file) {
        return getChecksum(file, "SHA1")
    }

    static getMD5sum(File file) {
        return getChecksum(file, "MD5")
    }

    static getChecksum(File file, String type) {
        def digest = MessageDigest.getInstance(type)
        def inputstream = file.newInputStream()
        def buffer = new byte[16384]
        int len

        while((len=inputstream.read(buffer)) > 0) {
            digest.update(buffer, 0, len)
        }
        def sha1sum = digest.digest()

        def result = ""
        for(byte b : sha1sum) {
            result += toHex(b)
        }
        return result
    }

    private static String hexChr(int b) {
        return Integer.toHexString(b & 0xF)
    }

    private static String toHex(int b) {
        return hexChr((b & 0xF0) >> 4) + hexChr(b & 0x0F)
    }

    private Data createData(File file)  {
        // Compute hash sum of the file
        //String checksum = getSha1sum(file)
        //QualifiedName outputQN = pFactory.newQualifiedName("http://example.com/", checksum, "ex");
        return pFactory.newData(Path.of(file.toString()), "http://example.com/", "ex")
    }
}
