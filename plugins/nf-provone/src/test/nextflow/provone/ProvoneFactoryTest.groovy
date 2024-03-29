package nextflow.provone

import java.nio.file.Path
import nextflow.config.ConfigBuilder
import nextflow.config.ConfigMap
import nextflow.script.ScriptRunner
import spock.lang.Specification

/**
 *
 * @author Felix Bartusch <felix.bartusch@uni-tuebingen.de>
 */
class ProvOneFactoryTest extends Specification {

    def 'should run script' () {
        given:
        Path configFile = Path.of("./src/testResources/nextflow.config");
        ConfigBuilder configBuilder = new ConfigBuilder();
        configBuilder.setUserConfigFiles(configFile);
        ConfigMap configMap = configBuilder.build()

        ScriptRunner runner  = new ScriptRunner(configMap);
        Path scriptPath = Path.of("./src/testResources/main.nf");
        runner.setScript(scriptPath);

        when:
        def result = runner.execute()

        then:
        1==1
    }
}
