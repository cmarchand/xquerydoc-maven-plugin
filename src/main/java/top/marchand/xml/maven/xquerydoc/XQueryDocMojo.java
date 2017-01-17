/*
 * Copyright [2017] Christophe Marchand
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
package top.marchand.xml.maven.xquerydoc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.apache.maven.doxia.sink.render.RenderingContext;
import org.apache.maven.doxia.siterenderer.sink.SiteRendererSink;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.reporting.MavenReport;
import org.apache.maven.reporting.MavenReportException;
import org.codehaus.doxia.sink.Sink;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.Commandline;

/**
 * Generates XQuery documentation, using https://github.com/xquery/xquerydoc/
 * TODO : xquerdoc implementation is extracted from sources, and not from 
 * release, should change this.
 * @author cmarchand
 */
@Mojo(name = "xquery-doc", threadSafe = true, defaultPhase = LifecyclePhase.PACKAGE)
public class XQueryDocMojo extends AbstractMojo implements MavenReport {
    @Parameter(required = true, defaultValue = "${project.build.directory}/xquerydoc")
    private File outputDirectory;
    
    @Parameter(defaultValue = "false", property = "maven.javadoc.skip")
    private boolean skip;
    
    @Parameter( defaultValue = "${basedir}/src/main/xquery")
    private File xqueryDirEntry;
    
//    @Parameter(alias = "keepConfigFile", defaultValue = "false")
//    private boolean keepGeneratedConfigFile;
//    
    @Parameter(defaultValue = "${basedir}", readonly = true)
    private File basedir;
    
//    @Parameter(defaultValue="${project.name}", readonly = true)
//    private String projectName;
//    
    @Parameter(defaultValue="${project.build.directory}/xquerydoc/__impl")
    private File implementationFolder;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if(skip) {
            getLog().info("Skipping xquery doc generation");
            return;
        }
        RenderingContext context = new RenderingContext( getOutputFolder(), getOutputName() + ".html" );
        SiteRendererSink sink = new SiteRendererSink( context );
        Locale locale = Locale.getDefault();
        try {
            generate( sink, locale );
        } catch (MavenReportException ex) {
            throw new MojoExecutionException("while generating XSL Documentation", ex);
        }
    }

    @Override
    public void generate(Sink sink, Locale locale) throws MavenReportException {
        try {
            initImplementation();
        } catch(URISyntaxException | IOException ex) {
            getLog().error("Unable to extract xquerydoc implementation", ex);
            return;
        }
        File indexFile = new File(getReportOutputDirectory(),getOutputName()+".html");
        indexFile.getParentFile().mkdirs();
        try {
            // starting as a fork
            Commandline cmd = new Commandline("java");
            cmd.addArg(createArgument("-Xmx1024m"));
            cmd.addArg(createArgument("-jar"));
            cmd.addArg(createArgument(new File(implementationFolder,"deps/xmlcalabash/calabash.jar").getAbsolutePath()));
            cmd.addArg(createArgument("-oresult="+indexFile.getAbsolutePath()));
            cmd.addArg(createArgument(new File(implementationFolder, "xquerydoc.xpl")));
            cmd.addArg(createArgument("xquery="+xqueryDirEntry.getAbsolutePath()));
            cmd.addArg(createArgument("output="+indexFile.getParentFile().getAbsolutePath()));
            cmd.addArg(createArgument("currentdir="+basedir.getAbsolutePath()));
            cmd.addArg(createArgument("format=html"));
            getLog().debug("CmdLine: "+cmd.toString());
            getLog().info("... this may take a while, be patient...");
            Process process = cmd.execute();
            // redirecting standard output
            BufferedReader is = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String s;
            do {
                s = is.readLine();
                getLog().info(s);
            } while(s!=null);
            is = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            do {
                s = is.readLine();
                getLog().info(s);
            } while(s!=null);
            int ret = process.waitFor();
            if(ret!=0) {
                // there is a bug, here
                throw new MavenReportException("gaulois-pipe exit with code "+ret);
            }
            copyResources();
        } catch(CommandLineException | IOException | InterruptedException ex) {
            getLog().error("while generating XQuery doc", ex);
        } finally {
            try {
                removeImplementation();
            } catch (IOException ex) {
                // ignore
            }
        }
    }

    @Override
    public String getOutputName() {
        return "xquerydoc/XQuery_documentation";
    }

    @Override
    public String getCategoryName() {
        return MavenReport.CATEGORY_PROJECT_REPORTS;
    }

    @Override
    public String getName(Locale locale) {
        return "XQuery Doc";
    }

    @Override
    public String getDescription(Locale locale) {
        return "XQuery documentation";
    }

    @Override
    public void setReportOutputDirectory(File file) {
        this.outputDirectory = file;
    }

    @Override
    public File getReportOutputDirectory() {
        return outputDirectory;
    }

    @Override
    public boolean isExternalReport() {
        return true;
    }

    @Override
    public boolean canGenerateReport() {
        return true;
    }
    
    private File getOutputFolder() {
        return new File(getReportOutputDirectory(),"xquerydoc");
    }

    /**
     * Extract from jar the implementation files
     */
    private void initImplementation() throws MalformedURLException, URISyntaxException, IOException {
        URL jarUrl = getJarUrl();
        ZipFile zf = new ZipFile(new File(jarUrl.toURI()));
        for(Enumeration<? extends ZipEntry> entries=zf.entries();entries.hasMoreElements();) {
            ZipEntry entry = entries.nextElement();
            if(entry.getName().startsWith("xquerydoc/") && !entry.isDirectory()) {
                // extract entry to implementation
                String destName = entry.getName().substring("xquerydoc/".length());
                // we do not extract root directory itself !
                if(destName.length()>0 ) {
                    File output = new File(implementationFolder, destName);
                    output.getParentFile().mkdirs();
                    BufferedInputStream is;
                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(output))) {
                        byte[] buffer = new byte[2048];
                        is = new BufferedInputStream(zf.getInputStream(entry));
                        int read = is.read(buffer);
                        while(read>0) {
                            bos.write(buffer, 0, read);
                            read = is.read(buffer);
                        }   bos.flush();
                    }
                    is.close();
                }
            }
        }
    }
    
    private void removeImplementation() throws IOException {
        FileUtils.deleteDirectory(implementationFolder);
    }
    
    private void copyResources() throws IOException {
        File lib = new File(getOutputFolder(),"lib");
        lib.mkdirs();
        Files.copy(new File(implementationFolder,"src/lib/prettify.js").toPath(), new File(lib,"prettify.js").toPath());
        Files.copy(new File(implementationFolder,"src/lib/prettify.css").toPath(), new File(lib,"prettify.css").toPath());
        Files.copy(new File(implementationFolder,"src/lib/lang-xq.js").toPath(), new File(lib,"lang-xq.js").toPath());
    }
    
    /**
     * Return the current plugin jar URL from classpath
     * @return 
     */
    private URL getJarUrl() throws MalformedURLException {
        URL resUrl = getClass().getClassLoader().getResource("top/marchand/xml/maven/xquerydoc-maven-plugin.xml");
        String externalForm = resUrl.toExternalForm();
        // remove everything after ! and the leading jar:
        String sJarUrl = externalForm.substring(0, externalForm.lastIndexOf("!"));
        return new URL(sJarUrl.substring(4));
    }
    
    private Commandline.Argument createArgument(String value) {
        Commandline.Argument arg = new Commandline.Argument();
        arg.setLine(value);
        return arg;
    }
    private Commandline.Argument createArgument(File file) {
        Commandline.Argument arg = new Commandline.Argument();
        arg.setLine("\""+file.getAbsolutePath()+"\"");
        return arg;
    }
}
