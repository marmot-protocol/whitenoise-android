import org.gradle.api.attributes.AttributeCompatibilityRule;
import org.gradle.api.attributes.CompatibilityCheckDetails;

/** Allows the isolated Android compiler classpath to consume ordinary JVM dependencies. */
public abstract class AndroidJvmCompatibility implements AttributeCompatibilityRule<String> {
    /** Accepts JVM-only libraries while retaining exact Android variant preference when available. */
    @Override
    public void execute(CompatibilityCheckDetails<String> details) {
        if (("androidJvm".equals(details.getConsumerValue()) && "jvm".equals(details.getProducerValue())) ||
                ("android".equals(details.getConsumerValue()) && "standard-jvm".equals(details.getProducerValue()))) {
            details.compatible();
        }
    }
}
