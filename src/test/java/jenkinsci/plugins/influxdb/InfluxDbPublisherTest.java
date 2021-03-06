package jenkinsci.plugins.influxdb;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.File;
import java.util.Collections;

@RunWith(PowerMockRunner.class)
@PowerMockIgnore({"com.sun.org.apache.xerces.*", "javax.xml.*", "org.xml.*", "org.w3c.*", "javax.activation.*"})
@PrepareForTest(Jenkins.class)
public class InfluxDbPublisherTest {

    @Rule
    public ExpectedException exception = ExpectedException.none();

    private FilePath workspace = new FilePath(new File("."));
    @Mock
    private Run build;
    @Mock
    private Launcher launcher;
    @Mock
    private TaskListener listener;

    @Test
    public void emptyTarget() throws Exception {
        exception.expect(RuntimeException.class);
        exception.expectMessage("Target was null!");

        InfluxDbPublisher.DescriptorImpl descriptorMock = Mockito.mock(InfluxDbPublisher.DescriptorImpl.class);
        Jenkins jenkinsMock = Mockito.mock(Jenkins.class);
        Mockito.when(descriptorMock.getTargets()).thenReturn(Collections.emptyList());
        Mockito.when(jenkinsMock.getDescriptorByType(InfluxDbPublisher.DescriptorImpl.class)).thenReturn(descriptorMock);
        PowerMockito.mockStatic(Jenkins.class);
        PowerMockito.when(Jenkins.getInstance()).thenReturn(jenkinsMock);

        try {
            new InfluxDbPublisher("").perform(build, workspace, launcher, listener);
        } catch (NullPointerException e) {
            Assert.fail("NullPointerException raised");
        }
    }
}
