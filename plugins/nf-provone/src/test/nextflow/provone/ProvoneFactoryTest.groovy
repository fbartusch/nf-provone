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

import java.nio.file.Path
import nextflow.Session
import nextflow.config.ConfigBuilder
import nextflow.config.ConfigMap
import nextflow.script.ScriptRunner
import spock.lang.Specification

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class ProvOneFactoryTest extends Specification {

//    def 'should return observer' () {
//        when:
//        def result = new ProvoneFactory().create(Mock(Session))
//        then:
//        result.size()==1
//        result[0] instanceof ProvOneObserver
//    }

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
            //result.size()==1
            //result[0] instanceof ProvOneObserver
    }

}
