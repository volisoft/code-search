package benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.RunnerException;
import scala.io.Source;
import scala.util.Random;
import voli.TestIO;
import voli.index.Index;
import voli.index.IndexRadix;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Created by vadym.oliinyk on 7/14/17.
 */
public class IndexPerformanceTest implements TestIO {

    public static void main(String[] args) throws RunnerException {
//        Options opt = new OptionsBuilder()
//                .include(".*.Bench*.*")
//                .warmupIterations(5)
//                .measurementIterations(5)
//                .measurementTime(TimeValue.milliseconds(3000))
//                .jvmArgsPrepend("-server")
//                .forks(1)
//                .build();
//
//        new Runner(opt).run();
        new IndexBench();
    }

    static Pattern pattern = Pattern.compile("[\\p{Z}\\s]+");

    static String[] split(String s) {
        return pattern.split(s);
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Fork(value = 1, jvmArgsAppend = {"-server", "-disablesystemassertions"})
    public static class IndexBench implements TestIO {
        static String randomStr() {
            return new Random().alphanumeric().take(1000).mkString("");
        }

        static final String html_file = Source.fromFile(
                IndexBench.class.getClassLoader().getResource("test").toString(),
                "UTF-8").mkString();

        static Index mi = new Index("");
        static IndexRadix ri = new IndexRadix("");

        @Benchmark
        @Warmup(iterations = 10, time = 3, timeUnit = TimeUnit.SECONDS)
        public void updateIndexMap() {
            mi.update(html_file, "");
        }

        @Benchmark
        @Warmup(iterations = 10, time = 3, timeUnit = TimeUnit.SECONDS)
        public void  updateIndexRadix() {
            ri.update(html_file, "");
        }
    }
}
