/*
 * "Copyright (c) 2014   Capgemini Technology Services (hereinafter "Capgemini")
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


package com.github.wuic.test.engine;

import com.github.wuic.config.ObjectBuilderFactory;
import com.github.wuic.engine.Engine;
import com.github.wuic.engine.EngineService;
import com.github.wuic.exception.UnableToInstantiateException;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * <p>
 * {@link com.github.wuic.engine.Engine} builder factory support for core module tests.
 * </p>
 *
 * @author Guillaume DROUET
 * @version 1.0
 * @since 0.4.0
 */
@RunWith(JUnit4.class)
public class EngineBuilderFactoryCoreTest {

    /**
     * The factory.
     */
    private static ObjectBuilderFactory<Engine> engineObjectBuilderFactory;

    /**
     * Initializes the factory.
     */
    @BeforeClass
    public static void initFactory() {
        engineObjectBuilderFactory = new ObjectBuilderFactory<Engine>(EngineService.class, EngineService.DEFAULT_SCAN_PACKAGE);
    }

    /**
     * Test for unknown builder.
     */
    @Test
    public void testCreateUnknownBuilder() throws UnableToInstantiateException {
        Assert.assertNull(engineObjectBuilderFactory.create("FooEngineBuilder"));
    }

    /**
     * Test for image compression.
     */
    @Test
    public void testCreateImageCompressorBuilder() throws UnableToInstantiateException {
        Assert.assertNotNull(engineObjectBuilderFactory.create("ImageCompressorEngineBuilder"));
    }

    /**
     * Test for CSS inspector.
     */
    @Test
    public void testCreateCssInspectorBuilder() throws UnableToInstantiateException {
        Assert.assertNotNull(engineObjectBuilderFactory.create("CssInspectorEngineBuilder"));
    }
}