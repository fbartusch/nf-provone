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
import nextflow.script.ScriptRunner
import spock.lang.Specification

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class ProvoneFactoryTest extends Specification {

    def 'should return observer' () {
        when:
        def result = new ProvoneFactory().create(Mock(Session))
        then:
        result.size()==1
        result[0] instanceof ProvOneObserver
    }

    def 'should run script' () {
        given:
            ScriptRunner runner  = new ScriptRunner();
            //System.out.println("Working Directory = " + System.getProperty("user.dir"));
            Path scriptPath = Path.of("./src/test/main.nf");
            runner.setScript(scriptPath);
        when:
            runner.execute()
            def result = new ProvoneFactory().create(Mock(Session))
        then:
            result.size()==1
            result[0] instanceof ProvOneObserver
    }

}
