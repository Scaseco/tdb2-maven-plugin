package org.aksw.maven.plugin.jena;

import java.util.Set;

import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Helper mojo to list the supported compression formats when building TDB2 database archives.
 */
@Mojo(name = "list-archive-formats", requiresProject = false, threadSafe = true)
public class ListArchiveFormatsMojo extends AbstractMojo {
    @Override
    public void execute() throws MojoExecutionException {
        Set<String> names = ArchiveStreamFactory.findAvailableArchiveOutputStreamProviders().keySet();
        getLog().info("Supported archive formats:");
        names.stream().sorted().forEach(n -> getLog().info(" - " + n));
    }
}
