package com.citytechinc.aem.groovy.console.services.impl

import com.citytechinc.aem.groovy.console.services.ConfigurationService
import com.citytechinc.aem.groovy.console.services.EmailService
import com.citytechinc.aem.groovy.console.services.GroovyConsoleService
import com.citytechinc.aem.groovy.extension.builders.NodeBuilder
import com.citytechinc.aem.groovy.extension.builders.PageBuilder
import com.day.cq.commons.jcr.JcrConstants
import com.day.cq.replication.ReplicationActionType
import com.day.cq.replication.ReplicationOptions
import com.day.cq.replication.Replicator
import com.day.cq.search.PredicateGroup
import com.day.cq.search.QueryBuilder
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager

import groovy.util.logging.Slf4j

import org.apache.commons.lang3.CharEncoding
import org.apache.felix.scr.ScrService
import org.apache.felix.scr.annotations.Activate
import org.apache.felix.scr.annotations.Component
import org.apache.felix.scr.annotations.Reference
import org.apache.felix.scr.annotations.Service
import org.apache.jackrabbit.util.Text
import org.apache.sling.api.SlingHttpServletRequest
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.osgi.framework.BundleContext
import org.slf4j.LoggerFactory

import javax.jcr.Binary
import javax.jcr.Node
import javax.jcr.Session

import static org.codehaus.groovy.control.customizers.builder.CompilerCustomizationBuilder.withConfig

@Service(GroovyConsoleService)
@Component
@Slf4j("LOG")
class DefaultGroovyConsoleService implements GroovyConsoleService {

    static final String RELATIVE_PATH_SCRIPT_FOLDER = "scripts"

    static final String CONSOLE_ROOT = "/etc/groovyconsole"

    static final String PARAMETER_FILE_NAME = "fileName"

    static final String PARAMETER_SCRIPT = "script"
    static final String PARAMETER_DRYRUN = "dryRun"

    static final String EXTENSION_GROOVY = ".groovy"

    static final def STAR_IMPORTS = ["javax.jcr", "org.apache.sling.api", "org.apache.sling.api.resource",
        "com.day.cq.search", "com.day.cq.tagging", "com.day.cq.wcm.api", "com.day.cq.replication"]

    static final def RUNNING_TIME = { closure ->
        def start = System.currentTimeMillis()

        closure()

        def date = new Date()

        date.time = System.currentTimeMillis() - start
        date.format("HH:mm:ss.SSS", TimeZone.getTimeZone("GMT"))
    }

    @Reference
    Replicator replicator

    @Reference
    QueryBuilder queryBuilder

    @Reference
    ConfigurationService configurationService

    @Reference
    EmailService emailService

    @Reference
    ScrService scrService

    BundleContext bundleContext
	
	Page page

    @Override
    Map<String, String> runScript(SlingHttpServletRequest request) {
        def resourceResolver = request.resourceResolver
        def session = resourceResolver.adaptTo(Session)
        def pageManager = resourceResolver.adaptTo(PageManager)

        def stream = new ByteArrayOutputStream()
        def binding = createBinding(request, stream)
        def configuration = createConfiguration()
        def shell = new GroovyShell(binding, configuration)

        def stackTrace = new StringWriter()
        def errorWriter = new PrintWriter(stackTrace)

        def result = ""
        def runningTime = ""
        def output = ""
        def error = ""
		List<String> changes = new ArrayList<String>()

        def scriptContent = request.getRequestParameter(PARAMETER_SCRIPT)?.getString(CharEncoding.UTF_8)
        def dryRun = request.getRequestParameter(PARAMETER_DRYRUN)?.getString(CharEncoding.UTF_8).toBoolean()

        try {
            LOG.info("DryRun={}", dryRun)
            def script = shell.parse(scriptContent)

            addMetaClass(resourceResolver, session, pageManager, script)

            runningTime = RUNNING_TIME {
                result = script.run()
                
                if (session.hasPendingChanges()) {
                    // list changes
					if (page) {
						changes = getListOfChanges(page.adaptTo(Resource), pageManager, null)
					}
					
                    if (!dryRun) {
                        session.save()
                        LOG.info("Session saved")
                    }
                }
                if (dryRun) {
                    LOG.info("Dry run, not saving session")
                    session.refresh(true)
                    session.logout()
                }
            }

            LOG.debug "script execution completed, running time = $runningTime"

            output = stream.toString(CharEncoding.UTF_8)

            saveOutput(session, output)

            if (!dryRun) {
                emailService.sendEmail(session, scriptContent, output, runningTime, true)
            }
        } catch (MultipleCompilationErrorsException e) {
            LOG.error("script compilation error", e)

            e.printStackTrace(errorWriter)

            error = stackTrace.toString()
        } catch (Throwable t) {
            LOG.error("error running script", t)

            t.printStackTrace(errorWriter)

            error = stackTrace.toString()

            if (!dryRun) {
                emailService.sendEmail(session, scriptContent, error, null, false)
            }
        } finally {
            stream.close()
            errorWriter.close()
        }

        [executionResult: result as String, outputText: output, stacktraceText: error, runningTime: runningTime, changes: changes]
    }

    @Override
    Map<String, String> saveScript(SlingHttpServletRequest request) {
        def name = request.getParameter(PARAMETER_FILE_NAME)
        def script = request.getParameter(PARAMETER_SCRIPT)

        def session = request.resourceResolver.adaptTo(Session)
        def folderNode = session.getNode(CONSOLE_ROOT).getOrAddNode(RELATIVE_PATH_SCRIPT_FOLDER, JcrConstants.NT_FOLDER)
        def fileName = name.endsWith(EXTENSION_GROOVY) ? name : "$name$EXTENSION_GROOVY"

        folderNode.removeNode(fileName)

        getScriptBinary(session, script).withBinary { Binary binary ->
            saveFile(session, folderNode, fileName, new Date(), "application/octet-stream", binary)
        }

        [scriptName: fileName]
    }
	
	List<String> getListOfChanges(Resource res, PageManager pageManager, List<String> changes) {
		if (changes == null) {
			changes = new ArrayList<String>()
		}
		
		def childIt = res.listChildren()
		
		while (childIt.hasNext()) {
			def childRes = childIt.next()
			Node childNode = childRes.adaptTo(Node)
			
			if (childNode != null && childNode.modified) {
				Page parentPage = pageManager.getContainingPage(childRes) 
				if (parentPage != null) {
					changes.add(parentPage.path)
				}
			}
			
			getListOfChanges(childRes, pageManager, changes)
		}
		
		changes
	}

    def createConfiguration() {
        def configuration = new CompilerConfiguration()

        withConfig(configuration) {
            imports {
                star STAR_IMPORTS.toArray(new String[STAR_IMPORTS.size()])
            }
        }
    }

    def createBinding(request, stream) {
        def printStream = new PrintStream(stream, true, CharEncoding.UTF_8)

        def resourceResolver = request.resourceResolver
        def session = resourceResolver.adaptTo(Session)

        new Binding([
            out: printStream,
            log: LoggerFactory.getLogger("groovyconsole"),
            session: session,
            slingRequest: request,
            pageManager: resourceResolver.adaptTo(PageManager),
            resourceResolver: resourceResolver,
            queryBuilder: queryBuilder,
            nodeBuilder: new NodeBuilder(session),
            pageBuilder: new PageBuilder(session),
            bundleContext: bundleContext
        ])
    }

    def addMetaClass(ResourceResolver resourceResolver, Session session, PageManager pageManager, Script script) {
        script.metaClass {
            delegate.getNode = { String path ->
                session.getNode(path)
            }

            delegate.getResource = { String path ->
                resourceResolver.getResource(path)
            }

            delegate.getPage = { String path ->
                page = pageManager.getPage(path)
            }

            delegate.move = { String src ->
                ["to": { String dst ->
                    session.move(src, dst)
                    session.save()
                }]
            }

            delegate.rename = { Node node ->
                ["to": { String newName ->
                    def parent = node.parent

                    delegate.move node.path to parent.path + "/" + newName

                    if (parent.primaryNodeType.hasOrderableChildNodes()) {
                        def nextSibling = node.nextSibling

                        if (nextSibling) {
                            parent.orderBefore(newName, nextSibling.name)
                        }
                    }

                    session.save()
                }]
            }

            delegate.copy = { String src ->
                ["to": { dst ->
                    session.workspace.copy(src, dst)
                }]
            }

            delegate.save = {
                session.save()
            }

            delegate.getService = { Class serviceType ->
                def serviceReference = bundleContext.getServiceReference(serviceType)

                bundleContext.getService(serviceReference)
            }

            delegate.getService = { String className ->
                def serviceReference = bundleContext.getServiceReference(className)

                bundleContext.getService(serviceReference)
            }

            delegate.getServices = { Class serviceType, String filter ->
                def serviceReferences = bundleContext.getServiceReferences(serviceType, filter)

                serviceReferences.collect { bundleContext.getService(it) }
            }

            delegate.getServices = { String className, String filter ->
                def serviceReferences = bundleContext.getServiceReferences(className, filter)

                serviceReferences.collect { bundleContext.getService(it) }
            }

            delegate.activate = { String path, ReplicationOptions options = null  ->
                replicator.replicate(session, ReplicationActionType.ACTIVATE, path, options)
            }

            delegate.deactivate = { String path, ReplicationOptions options = null  ->
                replicator.replicate(session, ReplicationActionType.DEACTIVATE, path, options)
            }

            delegate.doWhileDisabled = { String componentClassName, Closure closure ->
                def component = scrService.components.find { it.className == componentClassName }
                def result = null

                if (component) {
                    component.disable()

                    try {
                        result = closure()
                    } finally {
                        component.enable()
                    }
                } else {
                    result = closure()
                }

                result
            }

            delegate.createQuery { Map predicates ->
                queryBuilder.createQuery(PredicateGroup.create(predicates), session)
            }
        }
    }

    def saveOutput(Session session, String output) {
        if (configurationService.crxOutputEnabled) {
            def date = new Date()

            def folderPath = "${configurationService.crxOutputFolder}/${date.format('yyyy/MM/dd')}"
            def folderNode = session.rootNode

            folderPath.tokenize("/").each { name ->
                folderNode = folderNode.getOrAddNode(name, JcrConstants.NT_FOLDER)
            }

            def fileName = date.format("hhmmss")

            new ByteArrayInputStream(output.getBytes(CharEncoding.UTF_8)).withStream { stream ->
                session.valueFactory.createBinary(stream).withBinary { Binary binary ->
                    saveFile(session, folderNode, fileName, date, "text/plain", binary)
                }
            }
        }
    }

    def getScriptBinary(Session session, String script) {
        def binary = null

        new ByteArrayInputStream(script.getBytes(CharEncoding.UTF_8)).withStream { stream ->
            binary = session.valueFactory.createBinary(stream)
        }

        binary
    }

    void saveFile(Session session, Node folderNode, String fileName, Date date, String mimeType, Binary binary) {
        def fileNode = folderNode.addNode(Text.escapeIllegalJcrChars(fileName), JcrConstants.NT_FILE)
        def resourceNode = fileNode.addNode(JcrConstants.JCR_CONTENT, JcrConstants.NT_RESOURCE)

        resourceNode.set(JcrConstants.JCR_MIMETYPE, mimeType)
        resourceNode.set(JcrConstants.JCR_ENCODING, CharEncoding.UTF_8)
        resourceNode.set(JcrConstants.JCR_DATA, binary)
        resourceNode.set(JcrConstants.JCR_LASTMODIFIED, date.time)
        resourceNode.set(JcrConstants.JCR_LAST_MODIFIED_BY, session.userID)

        session.save()
    }

    @Activate
    void activate(BundleContext bundleContext) {
        this.bundleContext = bundleContext
    }
}
