import com.redhat.jenkins.nodesharing.ConfigRepoAdminMonitor
import com.redhat.jenkins.nodesharing.TaskLog
import hudson.Functions
import jenkins.model.Jenkins
import org.apache.commons.jelly.XMLOutput
import org.kohsuke.stapler.jelly.HTMLWriterOutput

def j = namespace(lib.JenkinsTagLib)
def l = namespace(lib.LayoutTagLib)
def st = namespace("jelly:stapler")
Jenkins jenkins = app
ConfigRepoAdminMonitor cram = my

l.layout(permission: app.ADMINISTER) {
    l.header(title: cram.displayName)
    l.main_panel {
        h1(cram.displayName)
        cram.errors.each { String context, Throwable ex ->
            h2(context)

            pre {
                if (ex instanceof TaskLog.TaskFailed) {
                    j.out(value: Functions.generateConsoleAnnotationScriptAndStylesheet())
                    XMLOutput output = getOutput()
                    ex.log.annotatedText.writeLogTo(0, output.asWriter())
                    //text(ex.log.readContent())
                } else {
                    text(Functions.printThrowable(ex))
                }
            }
        }
    }
}
