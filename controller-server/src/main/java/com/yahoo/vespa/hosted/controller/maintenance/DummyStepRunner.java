package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.vespa.hosted.controller.deployment.LockedStep;
import com.yahoo.vespa.hosted.controller.deployment.RunStatus;
import com.yahoo.vespa.hosted.controller.deployment.Step;

public class DummyStepRunner implements StepRunner {

    @Override
    public Step.Status run(LockedStep step, RunStatus run) {
        return Step.Status.succeeded;
    }

}
