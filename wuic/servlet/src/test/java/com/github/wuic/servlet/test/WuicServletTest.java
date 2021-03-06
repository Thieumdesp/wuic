/*
 * "Copyright (c) 2015   Capgemini Technology Services (hereinafter "Capgemini")
 *
 * License/Terms of Use
 * Permission is hereby granted, free of charge and for the term of intellectual
 * property rights on the Software, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to use, copy, modify and
 * propagate free of charge, anywhere in the world, all or part of the Software
 * subject to the following mandatory conditions:
 *
 * -   The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * Any failure to comply with the above shall automatically terminate the license
 * and be construed as a breach of these Terms of Use causing significant harm to
 * Capgemini.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, PEACEFUL ENJOYMENT,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
 * OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * Except as contained in this notice, the name of Capgemini shall not be used in
 * advertising or otherwise to promote the use or other dealings in this Software
 * without prior written authorization from Capgemini.
 *
 * These Terms of Use are subject to French law.
 *
 * IMPORTANT NOTICE: The WUIC software implements software components governed by
 * open source software licenses (BSD and Apache) of which CAPGEMINI is not the
 * author or the editor. The rights granted on the said software components are
 * governed by the specific terms and conditions specified by Apache 2.0 and BSD
 * licenses."
 */


package com.github.wuic.servlet.test;

import com.github.wuic.exception.WuicException;
import com.github.wuic.servlet.WuicServletContextListener;
import com.github.wuic.servlet.WuicServlet;
import com.github.wuic.test.Server;
import com.github.wuic.test.WuicConfiguration;
import com.github.wuic.test.WuicRunnerConfiguration;
import com.github.wuic.util.IOUtils;
import com.github.wuic.util.UrlMatcher;
import com.github.wuic.xml.ReaderXmlContextBuilderConfigurator;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import javax.xml.bind.JAXBException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;

/**
 * <p>
 * Tests for {@link com.github.wuic.servlet.WuicServlet}.
 * </p>
 *
 * @author Guillaume DROUET
 * @version 1.0
 * @since 0.5.0
 */
@RunWith(JUnit4.class)
@WuicRunnerConfiguration(webApplicationPath = "/servletTest", installServlet = WuicServlet.class, installListener = WuicServletContextListener.class)
public class WuicServletTest {

    /**
     * The server running during tests.
     */
    @ClassRule
    public static com.github.wuic.test.Server server = new Server();

    /**
     * XML configuration.
     */
    @Rule
    public WuicConfiguration configuration = new WuicConfiguration() {

        /**
         * {@inheritDoc}
         */
        @Override
        public void clearConfiguration() {
            WuicServletContextListener.getWuicFacade(server.getServletContext()).clearTag(getClass().getName());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void setWuicXmlReader(final Reader wuicXmlFile) throws JAXBException {
            try {
                WuicServletContextListener.getWuicFacade(server.getServletContext())
                        .configure(new ReaderXmlContextBuilderConfigurator(wuicXmlFile, getClass().getName(), true, null));
            } catch (WuicException e) {
                throw new RuntimeException(e);
            }
        }
    };

    /**
     * <p>
     * Executes a basic HTTP request and reads the response.
     * </p>
     *
     * @throws Exception if test fails
     */
    @Test
    public void servletTest() throws Exception {
        final InputStreamReader isr = new InputStreamReader(getClass().getResourceAsStream("/servletTest/wuic.xml"));
        configuration.setWuicXmlReader(new StringReader(IOUtils.readString(isr)));
        final String content = IOUtils.readString(new InputStreamReader(server.get("/wuic/heap/aggregate.css").getEntity().getContent()));
        Assert.assertTrue(content.contains("aggregate-me"));
    }

    /**
     * <p>
     * Bad URI test.
     * </p>
     *
     * @throws Exception if test fails
     */
    @Test
    public void badUriTest() throws Exception {
        final String content = IOUtils.readString(new InputStreamReader(server.get("/wuic/").getEntity().getContent()));
        Assert.assertTrue(content.contains(UrlMatcher.MATCHER_MESSAGE));
    }

    /**
     * <p>
     * Bad nut test.
     * </p>
     *
     * @throws Exception if test fails
     */
    @Test
    public void badNutTest() throws Exception {
        final InputStreamReader isr = new InputStreamReader(getClass().getResourceAsStream("/servletTest/wuic.xml"));
        configuration.setWuicXmlReader(new StringReader(IOUtils.readString(isr)));
        final String content = IOUtils.readString(new InputStreamReader(server.get("/wuic/heap/bad-nut").getEntity().getContent()));
        Assert.assertTrue(content, content.contains("The nut with name 'bad-nut' has not been found"));
    }

    /**
     * <p>
     * Bad workflow test
     * </p>
     *
     * @throws Exception if test fails
     */
    @Test
    public void badWorkflowTest() throws Exception {
        final String content = IOUtils.readString(new InputStreamReader(server.get("/wuic/bad/workflow").getEntity().getContent()));
        Assert.assertTrue(content, content.contains("The workflow identified with 'bad' could not be found"));
    }
}
