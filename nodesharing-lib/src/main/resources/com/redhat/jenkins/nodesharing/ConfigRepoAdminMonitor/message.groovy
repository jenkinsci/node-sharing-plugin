import com.redhat.jenkins.nodesharing.ConfigRepoAdminMonitor
import jenkins.model.Jenkins

def j = namespace(lib.JenkinsTagLib)
def st = namespace("jelly:stapler")
ConfigRepoAdminMonitor cram = my

div("class": "error") {
    dl {
        cram.errors.each { String context, Throwable ex ->
            dt("Node sharing pool: " + context)
            dd {
                text(ex.getMessage())
                st.nbsp()
                a(href: cram.url) {
                    text("Read more")
                }
            }
        }
    }
}
