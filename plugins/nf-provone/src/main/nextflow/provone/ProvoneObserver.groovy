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
import org.openprovenance.prov.model.Attribute
import org.openprovenance.prov.model.Document
import org.openprovenance.prov.interop.InteropFramework
import org.openprovenance.prov.interop.GenericInteropFramework
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
        document.setNamespace(ns)

        // Parse agent information
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
            this.fusekiURL = fusekiMap.containsKey("url") ? fusekiMap.get("url") : null
            this.fusekiUser = fusekiMap.containsKey("user") ? fusekiMap.get("user") : null
            this.fusekiPasword = fusekiMap.containsKey("password") ? fusekiMap.get("password") : null

            // Everything has to be defined
            if (! (fusekiURL && fusekiUser && fusekiPasword)) {
                log.debug "Fuseki: at least one of url, user, password is not defined"
            }

            //TODO Check here if Fuseki talks with us
        } else {
            log.debug "Parse Fuseki information: No information found!"
        }

        // Parse provenance file name
        this.provenanceFile = provoneParams.containsKey("file") ? provoneParams.get("file") : "provone_provenance.jsonld"

        // Create a new ProvOne workflow object and add it to the document
        workflow = pFactory.newWorkflow(session.workflowMetadata.getScriptId(),
                    session.workflowMetadata.getScriptName(),
                    session.workflowMetadata.getScriptFile().toString(),
                    session.workflowMetadata.getRepository(),
                    session.workflowMetadata.getCommitId(),
                    session.workflowMetadata.getRevision())
        document.getStatementOrBundle().add(workflow)

        // Execution metadata
        QualifiedName exeQN = pFactory.newQualifiedName("http://example.com/", session.workflowMetadata.getSessionId().toString(), "ex")
        Collection<Attribute> wfAttrs = new LinkedList<>()
        // Add projectDir as attribute
        wfAttrs.add(pFactory.newAttribute("http://example.com/", "projectDir", "ex",
                session.workflowMetadata.getProjectDir(), pFactory.getName().XSD_STRING))
        // Add launchDir as attribute
        wfAttrs.add(pFactory.newAttribute("http://example.com/", "launchDir", "ex",
                session.workflowMetadata.getLaunchDir(), pFactory.getName().XSD_STRING))
        // Add workDir as attribute
        wfAttrs.add(pFactory.newAttribute("http://example.com/", "workDir", "ex",
                session.workflowMetadata.getWorkDir(), pFactory.getName().XSD_STRING))
        // Add label
        wfAttrs.add(pFactory.newAttribute(Attribute.AttributeKind.PROV_LABEL,
                pFactory.newInternationalizedString(session.workflowMetadata.getRunName()),
                pFactory.getName().XSD_STRING))

        workflowExecution = pFactory.newExecution(exeQN, session.workflowMetadata.getStart(), null, wfAttrs)
        document.getStatementOrBundle().add(workflowExecution)

        // Associate user with workflow execution and plan (e.g. the Nextflow script)
        QualifiedName userWFAssocQN = pFactory.newQualifiedName("http://example.com/",
                user.getId().getLocalPart() + "_" + workflow.getId().getLocalPart(), "ex")
        WasAssociatedWith userWFAssoc = pFactory.newWasAssociatedWith(userWFAssocQN, workflowExecution.getId(), user.getId(), workflow.getId(), null)
        document.getStatementOrBundle().add(userWFAssoc)

        // Create Controller Provone Element
        QualifiedName controllerQN = pFactory.newQualifiedName("http://example.com/", "Nextflow", "ex")
        Collection<Attribute> controllerAttrs = new LinkedList<>()
        controllerAttrs.add(pFactory.newAttribute(Attribute.AttributeKind.PROV_LABEL, pFactory.newInternationalizedString("Nextflow"),
                pFactory.getName().XSD_STRING))
        // Add Build and timestamp as attribute
        controllerAttrs.add(pFactory.newAttribute("http://example.com/", "build", "ex",
                session.workflowMetadata.getNextflow().getBuild().toString(), pFactory.getName().XSD_STRING))
        controllerAttrs.add(pFactory.newAttribute("http://example.com/", "timestamp", "ex",
                session.workflowMetadata.getNextflow().getTimestamp(), pFactory.getName().XSD_STRING))
        controllerAttrs.add(pFactory.newAttribute("https://schema.org/", "softwareVersion", "schema",
                session.workflowMetadata.getNextflow().getVersion().toString(),
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
        InteropFramework intF = new GenericInteropFramework(this.pFactory)
        intF.writeDocument(this.provenanceFile, document)

        ApacheJenaInterop converter = new ApacheJenaInterop(this.pFactory)
        Model m = converter.createJenaModel(document)

        // Upload provenance to server
        try ( RDFConnection conn = RDFConnection.connectPW(fusekiURL, fusekiUser, fusekiPasword) ) {
            conn.load(m)
        } catch(Exception e) {
            log.error(e.toString())
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
