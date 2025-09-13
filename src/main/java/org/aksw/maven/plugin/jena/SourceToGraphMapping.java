package org.aksw.maven.plugin.jena;

import java.io.File;
import java.util.List;

import org.apache.maven.model.Dependency;

/**
 * Mapping of a source to a set of graphs.
 * Sources can be dependencies or files.
 */
public class SourceToGraphMapping {
    /** Source file to load. Mutually exclusive with 'dependency'. */
    protected File file;
    /** Source Maven artifact to load. Artifact format is g:a:v[:t[:c]]. Mutually exclusive with 'file'. */
    protected Dependency dependency;

    protected String format;

    /** Attempt lenient parse of the file or artifact. */
    protected boolean lenient;

    /** The options 'graph' and 'graphs' are mutually exclusive (not interpreted as union).*/
    protected String graph;

    /**
     * The special constants DEFAULT and ARTIFACT can be used for the default graph and the artifact graph (urn:mvn:g:a:v:t:c), respectively.
     * Artifact graph requires the 'dependency' field to be set.
     */
    protected List<String> graphs;

    public File getFile() { return file; }
    public void setFile(File file) { this.file = file; }
    public Dependency getDependency() { return dependency; }
    public void setDependency(Dependency dependency) { this.dependency = dependency; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public boolean isLenient() { return lenient; }
    public void setLenient(boolean lenient) { this.lenient = lenient;}
    public String getGraph() { return graph; }
    public void setGraph(String graph) { this.graph = graph; }
    public List<String> getGraphs() { return graphs; }
    public void setGraphs(List<String> graphs) { this.graphs = graphs; }
    @Override
    public String toString() {
        return "SourceToGraphMapping [file=" + file + ", dependency=" + dependency + ", format=" + format
                + ", lenient=" + lenient + ", graph=" + graph + ", graphs=" + graphs + "]";
    }
}
