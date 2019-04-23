/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.redhat.jenkins.nodesharingbackend;

import static com.redhat.jenkins.nodesharingbackend.ReservationVerifier.PlannedFixup.reduce;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.redhat.jenkins.nodesharing.ExecutorJenkins;
import com.redhat.jenkins.nodesharingbackend.ReservationVerifier.PlannedFixup;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReservationVerifierPlannedFixupTest {

    @Test
    public void args() {
        List<String> valid = l("foo", "bar");
        try {
            pf(null, valid);
            fail();
        } catch (IllegalArgumentException ex) {}
        try {
            pf(valid, null);
            fail();
        } catch (IllegalArgumentException ex) {}

        try {
            pf(valid, l("bar"));
            fail();
        } catch (IllegalArgumentException ex) {}
    }
    
    @Test
    public void simpleReduce() {
        assertEquals(
            pf(l("b"), l("x", "z")),
            reduce(
                    pf(l("a", "b"), l("x", "y", "z")),
                    pf(l("b"),      l("x", "z")),
                    pf(l("b"),      l("x", "y", "z"))
            )
        );

        assertEquals(
                pf(l(), l()),
                reduce(
                        pf(l("a"), l("x", "y", "z")),
                        pf(l("b"), l())
                )
        );
    }

    @Test
    public void fullPlanReduce() {
        ExecutorJenkins asdf = new ExecutorJenkins("http:as.df", "asdf");
        ExecutorJenkins ghjk = new ExecutorJenkins("http:gh.jk", "ghjk");
        Map<ExecutorJenkins, PlannedFixup> reduce = reduce(Arrays.asList(
                plan(asdf, pf(l(), l())),
                plan(ghjk, pf(l(), l()))
        ));
        assertEquals(0, reduce.size());

        reduce = reduce(Arrays.asList(
                plan(asdf, pf(l("foo", "baz"),  l("bar"))),
                plan(asdf, pf(l("foo"),         l("huh")))
        ));

        assertEquals(1, reduce.size());
        assertEquals(pf(l("foo"), l()), reduce.get(asdf));

    }

    private List<String> l(String... vals) {
        return new ArrayList<>(Arrays.asList(vals));
    }

    private PlannedFixup pf(List<String> c, List<String> s) {
        return new PlannedFixup(c, s);
    }

    private Map<ExecutorJenkins, PlannedFixup> plan(Object... vals) {
        assert vals.length % 2 == 0;

        Map<ExecutorJenkins, PlannedFixup> plan = new HashMap<>();
        for (int i = 0; i < vals.length; i+=2) {
            plan.put((ExecutorJenkins) vals[i], (PlannedFixup) vals[i+1]);
        }
        return plan;
    }
}
