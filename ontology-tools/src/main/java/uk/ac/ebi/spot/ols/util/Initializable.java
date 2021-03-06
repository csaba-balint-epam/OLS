package uk.ac.ebi.spot.ols.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An object that can do some initialization work in parallel at startup.  Implementing classes should extend {@link
 * #doInitialization} with the work they wish to do.  All methods that require initialization to complete can check the
 * current state with a call to {@link #isReady()} which returns true or false, or a call to {@link #waitUntilReady()},
 * which blocks whilst initialization completes.
 *
 * @author Tony Burdett
 * @date 25/01/12
 */
public abstract class Initializable {
    private Thread initThread;

    private boolean initStarted;
    private boolean ready;
    private Throwable initializationException;
    
    private Logger log = LoggerFactory.getLogger(getClass());

    protected Logger getLog() {
        return log;
    }

    protected void setInitStarted() {
    	getLog().trace("Thread.currentThread().id = " + Thread.currentThread().getId() + " setInitStarted.");
    	synchronized (this) {
	        this.initStarted = true;
	        notifyAll();
    	}
    }

    protected void setInitStopped() {
    	getLog().trace("Thread.currentThread().id = " + Thread.currentThread().getId() + " setInitStopped.");
    	synchronized (this) {
	        this.initStarted = false;
	        notifyAll();
    	}
    }

    protected void setReady(boolean ready) {
    	getLog().trace("Thread.currentThread().id = " + Thread.currentThread().getId() + " setReady.");
    	synchronized (this) {
	        this.ready = ready;
	        notifyAll();
    	}
    }

    protected void setInitializationException(Throwable t) {
    	synchronized (this) {
	        if (t != null) {
	            getLog().error("Failed to initialize " + Initializable.this.getClass().getSimpleName() + ". " +
	                                   "Initialization exception updated", t.getMessage());
	        }
	        this.initializationException = t;
	        notifyAll();
    	}
    }

    protected boolean hasInitStarted() throws IllegalStateException {
    	synchronized (this) {
    		getLog().trace("Thread.currentThread().id = " + Thread.currentThread().getId() + 
    				" initStarted = " + initStarted);
    		return initStarted;
    	}
    }

    public boolean isReady() throws IllegalStateException {
    	synchronized (this) {
	        if (initializationException != null) {
	            throw new IllegalStateException(
	                    "Initialization of " + getClass().getSimpleName() + " failed: " + initializationException.getMessage(), initializationException);
	        }
	        else {
	        	getLog().trace("Thread.currentThread().id = " + Thread.currentThread().getId() + 
	        			" ready = " + ready);
	            return ready;
	        }
    	}
    }

    public void waitUntilReady() throws IllegalStateException, InterruptedException {
    	getLog().trace("Thread.currentThread().id = " + Thread.currentThread().getId() + " waitUntilReady.");
    	synchronized (this) {
	        while (!isReady()) {
	        	Thread thread = Thread.currentThread();
	            getLog().debug("Thread with id = " + thread.getId() +  
	            		" Waiting until " + getClass().getSimpleName() + " is ready...");
	            wait();
	        }
	        getLog().debug(getClass().getSimpleName() + " is now ready");
    	}
    }

    protected void initOrWait() throws IllegalStateException, InterruptedException {
    	getLog().trace("Thread.currentThread().id = " + Thread.currentThread().getId() + 
    			" Current object id = " + this.hashCode() + " initOrWait.");
	    synchronized (this) {
	        if (hasInitStarted()) {
	            // init already started, just wait
	            waitUntilReady();
	        }
	        else {
	            // start (or possibly restart) init
	            if (!isReady()) {
	                init();
	                waitUntilReady();
	            }
	        }
    	}
    }

    protected void interrupt() {
    	getLog().trace("Thread.currentThread().id = " + Thread.currentThread().getId() + " interrupt.");
    	synchronized (this) {
	        // if initializing, then interrupt
	        if (hasInitStarted()) {
	            initThread.interrupt();
	            setInitStopped();
	        }
	        else {
	            if (!isReady()) {
	                setInitializationException(new InterruptedException("Initialization was forcibly interrupted"));
	            }
	        }
    	}
    }

    public void init() {
    	getLog().trace("Thread.currentThread().id = " + Thread.currentThread().getId() + " init.");
        // if not already started an init thread or fully initialized, then init
        synchronized (this) {
            if (!hasInitStarted() && !isReady()) {
                // clear any existing initialization exceptions and flag that init has started
                setInitializationException(null);
                setInitStarted();

                // now create new thread to do initialization
                initThread = new Thread((new Runnable() {
                    public void run() {
                        // call doInitialization() provided by subclasses
                        try {
                            getLog().debug("Initializing " + Initializable.this.getClass().getSimpleName() + "...");
                            doInitialization();
                            setReady(true);
                            getLog().debug("..." + Initializable.this.getClass().getSimpleName() + " initialized ok");
                        }
                        catch (Exception e) {
                            getLog().debug("Caught exception whilst initializing, " +
                                                   "attempting to handle with clean termination", e);
                            setInitializationException(e);
                        }
                        setInitStopped();
                    }
                }));
                getLog().trace("New initThread = " + initThread.getId() + " about to start.");
                // kick off init
                initThread.start();
            }
        }
    }

    public void destroy() {
    	getLog().trace("Thread.currentThread().id = " + Thread.currentThread().getId() + " destroy.");
        synchronized (this) {
            try {
                doTermination();
            }
            catch (Exception e) {
                getLog().error("Failed to terminate " + Initializable.this.getClass().getSimpleName() + ": " +
                                       "this may result in stale threads and a possible memory leak", e);
                setInitializationException(e);
            }
            interrupt();
            initThread = null;
        }
    }

    protected abstract void doInitialization() throws Exception;

    protected abstract void doTermination() throws Exception;    
}
