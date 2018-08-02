import com.redhat.jenkins.nodesharing.ConfigRepoAdminMonitor
import hudson.Functions
import jenkins.model.Jenkins

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
                text(Functions.printThrowable(ex))
            }
        }
    }
}
