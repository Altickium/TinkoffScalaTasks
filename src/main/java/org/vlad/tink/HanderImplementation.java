package org.vlad.tink;

import java.time.Duration;
import java.util.concurrent.*;

public class HanderImplementation implements Handler {

    private final Client client;
    private int retryCount = 0;
    HanderImplementation(Client client) {
        this.client = client;
    }

    @Override
    public ApplicationStatusResponse performOperation(String id) {
        ExecutorService executor = Executors.newFixedThreadPool(2);

        while (true) {
            Future<Response> future1 = executor.submit(() -> client.getApplicationStatus1(id));

            Future<Response> future2 = executor.submit(() -> client.getApplicationStatus2(id));

            Response result1 = null, result2 = null;
            long startTime = System.nanoTime();
            int delay = 100;
            while ((System.nanoTime() - startTime) / 1e-9 < 15) {
                try {
                    result1 = future1.get(delay, TimeUnit.MILLISECONDS);
                } catch (ExecutionException | TimeoutException e) {
                    executor.shutdown();
                    return new ApplicationStatusResponse.Failure(null, retryCount);
                } catch (InterruptedException e2){
                    // ignore
                }

                try {
                    result2 = future2.get(delay, TimeUnit.MILLISECONDS);
                } catch (ExecutionException | TimeoutException e) {
                    executor.shutdown();
                    return new ApplicationStatusResponse.Failure(null, retryCount);
                } catch (InterruptedException e2){
                    // ignore
                }

                Response result = result1;
                if (result2 != null) result = result2;
                if (result1 instanceof Response.RetryAfter){
                    retryCount++;
                }
                if (result2 instanceof Response.RetryAfter){
                    retryCount++;
                }

                if (result instanceof Response.Success) {
                    executor.shutdown();
                    return new ApplicationStatusResponse.Success(((Response.Success) result).applicationId(), ((Response.Success) result).applicationStatus());
                } else if (result instanceof Response.RetryAfter) {
                    future1.cancel(true);
                    future2.cancel(true);
                    try {
                        TimeUnit.SECONDS.sleep((((Response.RetryAfter) result).delay()).getSeconds());
                    } catch (Exception e){
                        executor.shutdown();
                        return new ApplicationStatusResponse.Failure(null, retryCount);
                    }
                    future1 = executor.submit(() -> client.getApplicationStatus1(id));
                    future2 = executor.submit(() -> client.getApplicationStatus2(id));
                } else if (result instanceof Response.Failure) {
                    executor.shutdown();
                    return new ApplicationStatusResponse.Failure(Duration.ofNanos(System.nanoTime()), retryCount);
                }
            }
        }
    }
}
