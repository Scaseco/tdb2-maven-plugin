package org.aksw.maven.plugin.jena;

import java.util.Set;

import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Helper mojo to list the supported (compression) encoding formats when building TDB2 database archives.
 */
@Mojo(name = "list-encoding-formats", requiresProject = false, threadSafe = true)
public class ListEncodingFormatsMojo extends AbstractMojo {
    @Override
    public void execute() throws MojoExecutionException {
        Set<String> names = CompressorStreamFactory.findAvailableCompressorOutputStreamProviders().keySet();
        getLog().info("Supported encoding formats:");
        names.stream().sorted().forEach(n -> getLog().info(" - " + n.toLowerCase()));
    }
}
