import nextflow.cli.PluginAbstractExec
import nextflow.plugin.BasePlugin
import org.pf4j.PluginWrapper

class ProvOnePlugin extends BasePlugin implements PluginAbstractExec {

    ProvOnePlugin(PluginWrapper wrapper) {
        super(wrapper)
    }

    @Override
    List<String> getCommands() {
        [ 'fmri' ]
    }

    @Override
    int exec(String cmd, List<String> args) {
        if( cmd == 'fmri' ) {
            println "Hello! You gave me these arguments: ${args.join(' ')}"
            return 0
        }
        else {
            System.err.println "Invalid command: ${cmd}"
            return 1
        }
    }
}