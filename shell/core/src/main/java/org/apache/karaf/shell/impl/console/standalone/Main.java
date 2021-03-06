/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.karaf.shell.impl.console.standalone;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import org.apache.felix.gogo.runtime.threadio.ThreadIOImpl;
import org.apache.felix.service.threadio.ThreadIO;
import org.apache.karaf.shell.api.action.lifecycle.Manager;
import org.apache.karaf.shell.api.console.Session;
import org.apache.karaf.shell.api.console.SessionFactory;
import org.apache.karaf.shell.api.console.Terminal;
import org.apache.karaf.shell.impl.action.command.ManagerImpl;
import org.apache.karaf.shell.impl.console.JLineTerminal;
import org.apache.karaf.shell.impl.console.SessionFactoryImpl;
import org.apache.karaf.shell.impl.console.TerminalFactory;
import org.apache.karaf.shell.support.NameScoping;
import org.apache.karaf.shell.support.ShellUtil;
import org.fusesource.jansi.AnsiConsole;

public class Main {

    private String application = System.getProperty("karaf.name", "root");
    private String user = "karaf";

    public static void main(String args[]) throws Exception {
        Main main = new Main();
        main.run(args);
    }

    /**
     * Use this method when the shell is being executed as a top level shell.
     *
     * @param args
     * @throws Exception
     */
    public void run(String args[]) throws Exception {

        ThreadIOImpl threadio = new ThreadIOImpl();
        threadio.start();

        InputStream in = unwrap(System.in);
        PrintStream out = wrap(unwrap(System.out));
        PrintStream err = wrap(unwrap(System.err));
        run(threadio, args, in, out, err);

        // TODO: do we need to stop the threadio that was started?
        // threadio.stop();
    }

    private void run(ThreadIO threadio, String[] args, InputStream in, PrintStream out, PrintStream err) throws Exception {
        StringBuilder sb = new StringBuilder();
        String classpath = null;
        boolean batch = false;
        String file = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--classpath=")) {
                classpath = arg.substring("--classpath=".length());
            } else if (arg.startsWith("-c=")) {
                classpath = arg.substring("-c=".length());
            } else if (arg.equals("--classpath") || arg.equals("-c")) {
                classpath = args[++i];
            } else if (arg.equals("-b") || arg.equals("--batch")) {
                batch = true;
            } else if (arg.startsWith("--file=")) {
                file = arg.substring("--file=".length());
            } else if (arg.startsWith("-f=")) {
                file = arg.substring("-f=".length());
            } else if (arg.equals("--file") || arg.equals("-f")) {
                file = args[++i];
            } else {
                sb.append(arg);
                sb.append(' ');
            }
        }

        if (file != null) {
            Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
            try {
                sb.setLength(0);
                for (int c = reader.read(); c >= 0; c = reader.read()) {
                    sb.append((char) c);
                }
            } finally {
                reader.close();
            }
        } else if (batch) {
            Reader reader = new BufferedReader(new InputStreamReader(System.in));
            sb.setLength(0);
            for (int c = reader.read(); c >= 0; reader.read()) {
                sb.append((char) c);
            }
        }

        ClassLoader cl = Main.class.getClassLoader();
        if (classpath != null) {
            List<URL> urls = getFiles(new File(classpath));
            cl = new URLClassLoader(urls.toArray(new URL[urls.size()]), cl);
        }

        SessionFactory sessionFactory = createSessionFactory(threadio);

        run(sessionFactory, sb.toString(), in, out, err, cl);
    }

    private void run(final SessionFactory sessionFactory, String command, final InputStream in, final PrintStream out, final PrintStream err, ClassLoader cl) throws Exception {

        final TerminalFactory terminalFactory = new TerminalFactory();
        try {
            String term = System.getenv("TERM");
            final Terminal terminal = new JLineTerminal(terminalFactory.getTerminal(), term);
            Session session = createSession(sessionFactory, command.length() > 0 ? null : in, out, err, terminal);
            session.put("USER", user);
            session.put("APPLICATION", application);

            discoverCommands(session, cl, getDiscoveryResource());

            if (command.length() > 0) {
                // Shell is directly executing a sub/command, we don't setup a console
                // in this case, this avoids us reading from stdin un-necessarily.
                session.put(NameScoping.MULTI_SCOPE_MODE_KEY, Boolean.toString(isMultiScopeMode()));
                session.put(Session.PRINT_STACK_TRACES, "execution");
                try {
                    session.execute(command);
                } catch (Throwable t) {
                    ShellUtil.logException(session, t);
                }

            } else {
                // We are going into full blown interactive shell mode.
                session.run();
            }
        } finally {
            terminalFactory.destroy();
        }
    }

    /**
     * Allow sub classes of main to change the ConsoleImpl implementation used.
     *
     * @param sessionFactory
     * @param in
     * @param out
     * @param err
     * @param terminal
     * @return
     * @throws Exception
     */
    protected Session createSession(SessionFactory sessionFactory, InputStream in, PrintStream out, PrintStream err, Terminal terminal) throws Exception {
        return sessionFactory.create(in, out, err, terminal, null, null);
    }

    protected SessionFactory createSessionFactory(ThreadIO threadio) {
        SessionFactoryImpl sessionFactory = new SessionFactoryImpl(threadio);
        sessionFactory.register(new ManagerImpl(sessionFactory, sessionFactory));
        return sessionFactory;
    }

    /**
     * Sub classes can override so that their registered commands do not conflict with the default shell
     * implementation.
     */
    public String getDiscoveryResource() {
        return "META-INF/services/org/apache/karaf/shell/commands";
    }

    protected void discoverCommands(Session session, ClassLoader cl, String resource) throws IOException, ClassNotFoundException {
        Manager manager = new ManagerImpl(session.getRegistry(), session.getFactory().getRegistry(), true);
        Enumeration<URL> urls = cl.getResources(resource);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            BufferedReader r = new BufferedReader(new InputStreamReader(url.openStream()));
            String line = r.readLine();
            while (line != null) {
                line = line.trim();
                if (line.length() > 0 && line.charAt(0) != '#') {
                    final Class<?> actionClass = cl.loadClass(line);
                    manager.register(actionClass);
                }
                line = r.readLine();
            }
            r.close();
        }
    }

    public String getApplication() {
        return application;
    }

    public void setApplication(String application) {
        this.application = application;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    /**
     * Returns whether or not we are in multi-scope mode.
     * <p/>
     * The default mode is multi-scoped where we prefix commands by their scope. If we are in single
     * scoped mode then we don't use scope prefixes when registering or tab completing commands.
     */
    public boolean isMultiScopeMode() {
        return true;
    }

    private static PrintStream wrap(PrintStream stream) {
        OutputStream o = AnsiConsole.wrapOutputStream(stream);
        if (o instanceof PrintStream) {
            return ((PrintStream) o);
        } else {
            return new PrintStream(o);
        }
    }

    private static <T> T unwrap(T stream) {
        try {
            Method mth = stream.getClass().getMethod("getRoot");
            return (T) mth.invoke(stream);
        } catch (Throwable t) {
            return stream;
        }
    }

    private static List<URL> getFiles(File base) throws MalformedURLException {
        List<URL> urls = new ArrayList<URL>();
        getFiles(base, urls);
        return urls;
    }

    private static void getFiles(File base, List<URL> urls) throws MalformedURLException {
        for (File f : base.listFiles()) {
            if (f.isDirectory()) {
                getFiles(f, urls);
            } else if (f.getName().endsWith(".jar")) {
                urls.add(f.toURI().toURL());
            }
        }
    }
}
