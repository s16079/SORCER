/*
 * Copyright 2010 the original author or authors.
 * Copyright 2010 SorcerSoft.org.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sorcer.core.provider.exerter;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import net.jini.core.lookup.ServiceID;
import net.jini.core.transaction.Transaction;
import net.jini.core.transaction.TransactionException;
import net.jini.core.transaction.server.TransactionManager;
import org.dancres.blitz.jini.lockmgr.LockResult;
import org.dancres.blitz.jini.lockmgr.MutualExclusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sorcer.core.SorcerConstants;
import sorcer.core.context.ControlContext;
import sorcer.core.context.ServiceContext;
import sorcer.core.context.ThrowableTrace;
import sorcer.core.context.model.ent.EntModel;
import sorcer.core.context.model.ent.Entry;
import sorcer.core.context.model.par.Par;
import sorcer.core.deploy.ServiceDeployment;
import sorcer.core.dispatch.DispatcherException;
import sorcer.core.dispatch.ExertionSorter;
import sorcer.core.dispatch.ProvisionManager;
import sorcer.core.exertion.ObjectTask;
import sorcer.core.provider.*;
import sorcer.core.signature.NetSignature;
import sorcer.core.signature.NetletSignature;
import sorcer.core.signature.ObjectSignature;
import sorcer.core.signature.ServiceSignature;
import sorcer.jini.lookup.ProviderID;
import sorcer.netlet.ScriptExerter;
import sorcer.service.*;
import sorcer.service.Exec.State;
import sorcer.service.Strategy.Access;
import sorcer.service.modeling.Model;
import sorcer.service.modeling.ModelingTask;
import sorcer.service.txmgr.TransactionManagerAccessor;
import sorcer.util.Sorcer;

import java.io.File;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static sorcer.eo.operator.*;

/**
 * @author Mike Sobolewski
 */
@SuppressWarnings("rawtypes")
public class ServiceShell implements Shell, Service, Servicer, Exerter, Callable {
	protected final static Logger logger = LoggerFactory.getLogger(ServiceShell.class);
	private Service service;
	private Mogram mogram;
	private File mogramSource;
	private Transaction transaction;
	private static MutualExclusion locker;
	// a refrence to a provider running this mogram
	private Service provider;
	private static LoadingCache<Signature, Object> proxies;

	public ServiceShell() {
		setupProxyCache();
	}

	public ServiceShell(Mogram mogram) {
		this();
		this.mogram = mogram;
	}

	public ServiceShell(Mogram mogram, Transaction txn) {
		this();
		this.mogram = mogram;
		transaction = txn;
	}

	public void init(Provider provider) {
		this.provider = provider;
	}

	private static void setupProxyCache() {
		proxies =  CacheBuilder.newBuilder()
				.maximumSize(20)
				.expireAfterWrite(30, TimeUnit.MINUTES)
//				.removalListener(null)
				.build(new CacheLoader<Signature, Object>() {
					public Object load(Signature signature) {
						return Accessor.get().getService(signature);
					}
				});
	}

	public <T extends Mogram> T  exert(Arg... entries) throws TransactionException,
			MogramException, RemoteException {
		return exert((Transaction) null, (String) null, entries);
	}

	public Mogram exert(Mogram xrt, Arg... entries)
			throws TransactionException, MogramException, RemoteException {
		try {
			xrt.substitute(entries);
		} catch (Exception e) {
			throw new ExertionException(e);
		}
		return exert(xrt, null, (String) null);
	}

	/* (non-Javadoc)
	 * @see sorcer.service.Exerter#exert(sorcer.service.Exertion, net.jini.core.transaction.Transaction, sorcer.service.Parameter[])
	 */
	@Override
	public  <T extends Mogram> T exert(T input, Transaction transaction, Arg... entries) throws ExertionException {
		try {
			Mogram result = null;
			try {
				if (input instanceof Exertion) {
					Exertion exertion = ((Exertion)input);
					if ((input.getProcessSignature() != null
							&& ((ServiceSignature) input.getProcessSignature()).isShellRemote())
							|| (exertion.getControlContext() != null
							&& ((ControlContext) exertion.getControlContext()).isShellRemote())) {
						Exerter prv = (Exerter) Accessor.get().getService(sig(Shell.class));
						result = prv.exert(input, transaction, entries);
					} else {
						try {
							input.substitute(entries);
						} catch (Exception e) {
							throw new ExertionException(e);
						}
						mogram = input;
						result = exert(transaction, null);
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
				if (result != null)
					((ServiceExertion) result).reportException(e);
			}
			return (T) result;
		} catch (Exception e) {
			throw new ExertionException(e);
		}
	}

	public  <T extends Mogram> T exert(String providerName) throws TransactionException,
			MogramException, RemoteException {
		return exert(null, providerName);
	}

	@Override
	public <T extends Mogram> T service(T mogram, Transaction txn)
			throws TransactionException, MogramException, RemoteException {
		if (service != null)
			return service.service(mogram, txn);
		else
			return (T) exert(mogram, txn);
	}

	public <T extends Mogram> T service(T mogram)
			throws TransactionException, MogramException, RemoteException {
		return  service(mogram, null);
	}

	public  <T extends Mogram> T exert(T mogram, Transaction txn, String providerName)
			throws TransactionException, MogramException, RemoteException {
		this.mogram = mogram;
		transaction = txn;
		return exert(txn, providerName);
	}


	public <T extends Mogram> T  exert(Transaction txn, String providerName, Arg... entries)
			throws TransactionException, MogramException, RemoteException {
		try {
			if (mogram instanceof Exertion) {
				ServiceExertion exertion = (ServiceExertion)mogram;
				exertion.selectFidelity(entries);
				Mogram out = exerting(txn, providerName, entries);
				if (out instanceof Exertion)
					postProcessExertion(out);
				if (exertion.isProxy()) {
					Exertion xrt = (Exertion) out;
					exertion.setContext(xrt.getDataContext());
					exertion.setControlContext((ControlContext) xrt.getControlContext());
					if (exertion.isCompound()) {
						((CompoundExertion) exertion).setMograms(xrt.getMograms());
					}
					return (T) xrt;
				} else {
					return (T) out;
				}
			} else {
				((Model)mogram).getResponse();
				return (T) mogram;
			}
		} catch (ContextException e) {
			throw new ExertionException(e);
		}
	}

	private void initExecState(Arg... entries) throws MogramException, RemoteException {
		Context argCxt = null;
		if (entries!=null) {
			for (Arg arg : entries) {
				if (arg instanceof Context && ((Context)arg).size() > 0) {
					argCxt = (Context)arg;
				}
			}
		}
//		if (exertion instanceof Block) {
//			resetScope(exertion, argCxt, entries);
//		}
//		else if (exertion.getScope() != null) {
//			exertion.getDataContext().append((Context)exertion.getScope());
//		}
		Exec.State state = ((ServiceExertion)mogram).getControlContext().getExecState();
		if (state == State.INITIAL) {
			if(mogram instanceof Exertion) {
				mogram.getExceptions().clear();
				mogram.getTrace().clear();
			}
			for (Mogram e : ((Exertion)mogram).getAllMograms()) {
				if (e instanceof Exertion) {
					if (((ControlContext) ((Exertion)e).getControlContext()).getExecState() == State.INITIAL) {
						e.setStatus(Exec.INITIAL);
						e.getExceptions().clear();
						e.getTrace().clear();
					}
				}
				if (e instanceof Block) {
					resetScope((Exertion)e, argCxt);
				} else {
					e.clearScope();
				}
			}
		}
	}

	private void resetScope(Exertion exertion, Context context, Arg... entries) throws MogramException, RemoteException {
		((ServiceContext)exertion.getDataContext()).clearScope();
		exertion.getDataContext().append(((ServiceContext)exertion.getDataContext()).getInitContext());
		if (entries != null) {
			for (Arg a : entries) {
				if (a instanceof Entry) {
					exertion.getContext().putValue(
							((Entry) a).path(), ((Entry) a).value());
				}
			}
		}
		if (context != null) {
			exertion.getDataContext().append(context);
		}
		for (Mogram mogram : exertion.getMograms()) {
			mogram.clearScope();
		}
	}

	private void realizeDependencies(Arg... entries) throws RemoteException,
			ExertionException {
		List<Evaluation> dependers = ((ServiceExertion)mogram).getDependers();
		if (dependers != null && dependers.size() > 0) {
			for (Evaluation<Object> depender : dependers) {
				try {
					((Invocation)depender).invoke(mogram.getScope(), entries);
				} catch (InvocationException e) {
					throw new ExertionException(e);
				}
			}
		}
	}

	private Exertion initExertion(ServiceExertion exertion, Transaction txn, Arg... entries) throws ExertionException {
		try {
			if (entries != null && entries.length > 0) {
				exertion.substitute(entries);
			}
			// check if the exertion has to be initialized (to original state)
			// or used as is after resuming from suspension or failure
			if (exertion.isInitializable()) {
				initExecState(entries);
			}
			realizeDependencies(entries);
			if (exertion.isTask() && exertion.isProvisionable()) {
				try {
					List<ServiceDeployment> deploymnets = exertion.getDeploymnets();
					if (deploymnets.size() > 0) {
						ProvisionManager provisionManager = new ProvisionManager(exertion);
						provisionManager.deployServices();
					}
				} catch (DispatcherException e) {
					throw new ExertionException(
							"Unable to deploy services for: "
									+ mogram.getName(), e);
				}
			}
//			//TODO disabled due to problem with monitoring. Needs to be fixed to run with monitoring
//			if (exertion instanceof Job && ((Job) exertion).size() == 1) {
//				return processAsTask();
//			}
			transaction = txn;
			Context<?> cxt = exertion.getDataContext();
			if (cxt != null)
				cxt.setExertion(exertion);
		} catch (Exception ex) {
			ex.printStackTrace();
			throw new ExertionException(ex);
		}
		return exertion;
	}

	private Exertion processAsTask() throws RemoteException,
			TransactionException, MogramException, SignatureException {
		Exertion exertion = (Exertion)mogram;
		Task task = (Task) exertion.getMograms().get(0);
		task = task.doTask();
		exertion.getMograms().set(0, task);
		exertion.setStatus(task.getStatus());
		return exertion;
	}

	private Exertion dispatchExertion(ServiceExertion exertion, String providerName)
			throws ExertionException, ExecutionException {
		Signature signature = exertion.getProcessSignature();
		Object provider = null;
		try {
			// If the exertion is a job rearrange the inner exertions to make sure the
			// dependencies are not broken
			if (exertion.isJob()) {
				ExertionSorter es = new ExertionSorter(exertion);
				exertion = (ServiceExertion)es.getSortedJob();
			}
//			 execute modeling tasks
			if (exertion instanceof ModelingTask && exertion.getFidelity().getSelects().size() == 1) {
				return ((Task) exertion).doTask(transaction);
			}

			// handle delegated tasks with fidelities
			if (exertion.getClass() == Task.class) {
				if (exertion.getFidelity().getSelects().size() == 1) {
					return ((Task) exertion).doTask(transaction);
				} else {
					try {
						return new ControlFlowManager().doTask((Task) exertion);
					} catch (ContextException e) {
						e.printStackTrace();
						throw new ExertionException(e);
					}
				}
			}

			// execute object tasks and jobs
			if (!(signature instanceof NetSignature)) {
				if (exertion instanceof Task) {
					if (exertion.getFidelity().getSelects().size() == 1) {
						return ((Task) exertion).doTask(transaction);
					} else {
						try {
							return new ControlFlowManager().doTask((Task) exertion);
						} catch (ContextException e) {
							e.printStackTrace();
							throw new ExertionException(e);
						}
					}
				} else if (exertion instanceof Job) {
					return ((Job) exertion).doJob(transaction);
				} else if (exertion instanceof Block) {
					return ((Block) exertion).doBlock(transaction);
				}
			}
			// check for missing signature of inconsistent PULL/PUSH cases
			logger.info("signature (before) = {}", signature);
			signature = correctProcessSignature();
			logger.info("signature (after)  = {}", signature);

			if (!((ServiceSignature) signature).isSelectable()) {
				exertion.reportException(new ExertionException(
						"No such operation in the requested signature: "+ signature));
				logger.warn("Not selectable exertion operation: " + signature);
				return exertion;
			}

			if (providerName != null && providerName.length() > 0) {
				signature.setProviderName(providerName);
			}
			if (logger.isDebugEnabled())
				logger.debug("ServiceShell's service accessor: {}", Accessor.get().getClass().getName());
			provider = ((NetSignature) signature).getProvider();
			if (provider == null) {
				// check proxy cache
                try {
                    provider = proxies.get(signature);
                } catch(CacheLoader.InvalidCacheLoadException e) {
                    String message =
                        String.format("Provider name: [%s], type: %s not found, make sure it is running and there is " +
                                      "an available lookup service with correct discovery settings",
                                      signature.getProviderName(), signature.getServiceType().getName());
                    logger.error(message);
                    throw new ExertionException(message);
                }
				// lookup proxy
				/*if (provider == null) {
					long t0 = System.currentTimeMillis();
					provider = Accessor.get().getService(signature);
					if (logger.isDebugEnabled())
					 logger.info("Return from Accessor.getService(), round trip: {} millis",
							 (System.currentTimeMillis() - t0));
				}*/
			}
		} catch (Exception e) {
			throw new ExertionException(e);
		}

		if (!exertion.isJob()
				&& exertion.getControlContext().getAccessType() == Access.PULL) {
			signature = new NetSignature("service", Spacer.class, Sorcer.getActualSpacerName());
			exertion.setProcessSignature(signature);
		}
 		if (provider != null) {
			if (provider instanceof Service) {
				// cache the provider for the signature
				((NetSignature) signature).setProvider((Service) provider);
				proxies.put(signature, provider);
			} else if (exertion instanceof Task){
				// exert smart proxy as an object task delegate
				try {
					ObjectSignature sig = new ObjectSignature();
					sig.setSelector(signature.getSelector());
					sig.setTarget(provider);
					Context cxt = exertion.getContext();
					((Task)exertion).setDelegate(new ObjectTask(sig, cxt));
					return ((Task)exertion).doTask(transaction);
				} catch (Exception e) {
					throw new ExertionException(e);
				}
			}
		}
		this.provider = (Service)provider;
		// continue exerting
		return null;
	}

	private Exertion serviceExertion(ServiceExertion exertion, Signature signature)
			throws TransactionException, MogramException, RemoteException {
		if (provider == null) {
			logger.warn("* Provider not available for: " + signature);
			exertion.setStatus(Exec.FAILED);
			exertion.reportException(new RuntimeException(
					"Cannot find provider for: " + signature));
			return exertion;
		}
		exertion.trimAllNotSerializableSignatures();
		exertion.getControlContext().appendTrace(
				"shell: " + ((Provider) provider).getProviderName()
						+ ":" + ((Provider) provider).getProviderID());
		logger.info("Provider found for: " + signature + "\n\t" + provider);
		if (((Provider) provider).mutualExclusion()) {
			return serviceMutualExclusion((Provider) provider, exertion,
					transaction);
		} else {
//			 test exertion for serialization
//						 try {
//							 logger.info("ExertProcessor.exert0(): going to serialize exertion for testing!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
//							 ObjectLogger.persistMarshalled("exertionfile", exertion);
//						 } catch (Exception e) {
//							 e.printStackTrace();
//						 }
			Exertion result = provider.service(exertion, transaction);
			if (result != null && result.getExceptions().size() > 0) {
				for (ThrowableTrace et : result.getExceptions()) {
					Throwable t = et.getThrowable();
					logger.error("Got exception running: {}", exertion.getName(), t);
					if (t instanceof Error)
						result.setStatus(Exec.ERROR);
				}
				result.setStatus(Exec.FAILED);
			} else if (result == null) {
				exertion.reportException(new ExertionException("ExertionDispatcher failed calling: "
						+ exertion.getProcessSignature()));
				exertion.setStatus(Exec.FAILED);
				result = exertion;
			}
			return result;
		}
	}

	public Mogram exerting(Transaction txn, String providerName, Arg... entries)
			throws TransactionException, MogramException, RemoteException {
		ServiceExertion exertion = (ServiceExertion) mogram;
		initExertion(exertion, txn, entries);
		Exertion xrt;
		try {
			xrt = dispatchExertion(exertion, providerName);
		} catch (Exception e) {
			throw new MogramException(e);
		}
		if (xrt !=  null)
			return xrt;
		return serviceExertion(exertion, exertion.getProcessSignature());
	}

	private Exertion serviceMutualExclusion(Provider provider,
											Exertion exertion, Transaction transaction) throws RemoteException,
			TransactionException, MogramException {
		ServiceID mutexId = provider.getProviderID();
		if (locker == null) {
			locker = Accessor.get().getService(null, MutualExclusion.class);
		}
		TransactionManager transactionManager = TransactionManagerAccessor.getTransactionManager();
		Transaction txn = null;

        LockResult lr = locker.getLock(""+ exertion.getProcessSignature().getServiceType(),
                                       new ProviderID(mutexId),
                                       txn,
                                       exertion.getId());
        if (lr.didSucceed()) {
			((ControlContext)exertion.getControlContext()).setMutexId(provider.getProviderID());
			Exertion xrt = provider.service(exertion, transaction);
			txn.commit();
			return xrt;
		} else {
			// try continue to get lock, if failed abort the transaction txn
			txn.abort();
		}
		exertion.getControlContext().addException(
				new ExertionException("no lock available for: "
						+ provider.getProviderName() + ":"
						+ provider.getProviderID()));
		return exertion;
	}

	/**
	 * Depending on provider access type correct inconsistent signatures for
	 * composite exertions only. Tasks go either to its provider directly or
	 * Spacer depending on their provider access type (PUSH or PULL).
	 *
	 * @return the corrected signature
	 */
	public Signature correctProcessSignature() {
		ServiceExertion exertion = (ServiceExertion)mogram;
		if (!exertion.isJob())
			return exertion.getProcessSignature();
		Signature sig = exertion.getProcessSignature();
		if (sig != null) {
			Access access = exertion.getControlContext().getAccessType();
			if (Access.PULL == access
					&& !mogram.getProcessSignature().getServiceType()
					.isAssignableFrom(Spacer.class)) {
				sig.setServiceType(Spacer.class);
				((NetSignature) sig).setSelector("service");
				sig.setProviderName(SorcerConstants.ANY);
				sig.setType(Signature.Type.PROC);
				exertion.getControlContext().setAccessType(access);
			} else if (Access.PUSH == access
					&& !sig.getServiceType()
					.isAssignableFrom(Jobber.class)) {
				if (sig.getServiceType().isAssignableFrom(Spacer.class)) {
					sig.setServiceType(Jobber.class);
					((NetSignature) sig).setSelector("service");
					sig.setProviderName(SorcerConstants.ANY);
					sig.setType(Signature.Type.PROC);
					exertion.getControlContext().setAccessType(access);
				}
			}
		} else {
			sig = new NetSignature("service", Jobber.class);
		}
		return sig;
	}

	public static Mogram postProcessExertion(Mogram mog)
			throws ContextException, RemoteException {
		if (mog instanceof Exertion) {
			List<Mogram> mograms = ((Exertion)mog).getAllMograms();
			for (Mogram mogram : mograms) {
				if (mogram instanceof Exertion) {
					List<Setter> ps = ((ServiceExertion) mogram).getPersisters();
					if (ps != null) {
						for (Setter p : ps) {
							if (p != null && (p instanceof Par) && ((Par) p).isMappable()) {
								String from = ((Par) p).getName();
								Object obj;
								if (mogram instanceof Job)
									obj = ((Job) mogram).getJobContext().getValue(from);
								else {
									obj = ((Exertion) mogram).getContext().getValue(from);
								}

								if (obj != null)
									p.setValue(obj);
							}
						}
					}
				}
			}
		}
		return mog;
	}

	private boolean isShellRemote() {
		return provider != null;
	}

	public Transaction getTransaction() {
		return transaction;
	}

	public void setTransaction(Transaction transaction) {
		this.transaction = transaction;
	}

	public File getMogramSource() {
		return mogramSource;
	}

	public void setMogramSource(File mogramSource) {
		this.mogramSource = mogramSource;
	}

	@Override
	public String toString() {
		if (mogram == null)
			return "ServiceShell";
		else
			return "ServiceShell for: " + mogram.getName();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.util.concurrent.Callable#call()
	 */
	@Override
	public Object call() throws Exception {
		return mogram.exert(transaction);
	}

	public Object evaluate(Arg... args)
			throws ExertionException, RemoteException, ContextException {
		return evaluate(mogram, args);
	}

	public Object evaluate(Mogram mogram, Arg... args)
			throws ExertionException, ContextException, RemoteException {
		if (mogram instanceof Exertion) {
			Exertion exertion = (Exertion)mogram;
			Object out;
			initialize(exertion, args);
			try {
				if (exertion.getClass() == Task.class) {
					if (((Task) exertion).getDelegate() != null)
						out = exert(((Task) exertion).getDelegate(), null, args);
					else
						out = exertOpenTask(exertion, args);
				} else {
					if (exertion instanceof Task && ((Task)exertion).getDelegate() != null) {
						out = ((Task)exertion).doTask();
					} else {
						out = exert(exertion, null, args);
					}
				}
				return finalize((Exertion) out, args);
			} catch (Exception e) {
				logger.error("Failed in evaluate", e);
				throw new ExertionException(e);
			}
		} else {
			return ((Model)mogram).getResponse();
		}
	}

	private static Exertion initialize(Exertion xrt, Arg... args) throws ContextException {
		Signature.ReturnPath rPath = null;
		for (Arg a : args) {
			if (a instanceof Signature.ReturnPath) {
				rPath = (Signature.ReturnPath) a;
				break;
			}
		}
		if (rPath != null)
			((ServiceContext)xrt.getDataContext()).setReturnPath(rPath);
		return xrt;
	}

	private static Object finalize(Exertion xrt, Arg... args) throws ContextException, RemoteException {
		Context dcxt = xrt.getDataContext();
		Signature.ReturnPath rPath =	dcxt.getReturnPath();
		// check if it was already finalized
		if (((ServiceContext) dcxt).isFinalized()) {
			return dcxt.getValue(rPath.path);
		}
		// get the compound service context
		Context acxt = xrt.getContext();

		if (rPath != null && xrt.isCompound()) {
			// if Path.outPaths.length > 1 return subcontext
			if (rPath.outPaths != null && rPath.outPaths.length == 1) {
				Object val = acxt.getValue(rPath.outPaths[0]);
				dcxt.putValue(rPath.path, val);
				return val;
			} else {
				Signature.ReturnPath rp = ((ServiceContext) dcxt).getReturnPath();
				if (rp != null && rPath.path != null) {
					Object result = acxt.getValue(rp.path);
					if (result instanceof Context)
						return ((Context) acxt.getValue(rp.path))
								.getValue(rPath.path);
					else if (result == null) {
						Context out = new ServiceContext();
						logger.debug("\nselected paths: " + Arrays.toString(rPath.outPaths)
								+ "\nfrom context: " + acxt);
						for (String p : rPath.outPaths) {
							out.putValue(p, acxt.getValue(p));
						}
						dcxt.setReturnValue(out);
						result = out;
					}
					return result;
				} else {
					return xrt.getContext().getValue(rPath.path);
				}
			}
		} else if (rPath != null) {
			if (rPath.outPaths != null) {
				if (rPath.outPaths.length == 1) {
					Object val = acxt.getValue(rPath.outPaths[0]);
					acxt.putValue(rPath.path, val);
					return val;
				} else if (rPath.outPaths.length > 1) {
					Object result = acxt.getValue(rPath.path);
					if (result instanceof Context)
						return result;
					else {
						Context cxtOut = ((ServiceContext) acxt).getSubcontext(rPath.outPaths);
						cxtOut.putValue(rPath.path, result);
						return cxtOut;
					}
				}
			}
		}

		Object obj = xrt.getReturnValue(args);
		if (obj == null) {
			if (rPath != null) {
				return xrt.getReturnValue(args);
			} else {
				return xrt.getContext();
			}
		} else if (obj instanceof Context && rPath != null && rPath.path != null) {
			return (((Context)obj).getValue(rPath.path));
		}
		return obj;
	}

	public static Exertion exertOpenTask(Exertion exertion, Arg... args)
			throws ExertionException {
		Exertion closedTask = null;
		List<Arg> params = Arrays.asList(args);
		List<Object> items = new ArrayList<Object>();
		for (Arg param : params) {
			if (param instanceof ControlContext
					&& ((ControlContext) param).getSignatures().size() > 0) {
				List<Signature> sigs = ((ControlContext) param).getSignatures();
				ControlContext cc = (ControlContext) param;
				cc.setSignatures(null);
				Context tc;
				try {
					tc = exertion.getContext();
				} catch (ContextException e) {
					throw new ExertionException(e);
				}
				items.add(tc);
				items.add(cc);
				items.addAll(sigs);
				closedTask = task(exertion.getName(), items.toArray());
			}
		}
		try {
			closedTask = closedTask.exert(args);
		} catch (Exception e) {
			e.printStackTrace();
			throw new ExertionException(e);
		}
		return closedTask;
	}

	public <T extends Mogram> T exec(Service srv, Mogram mog, Transaction txn)
			throws TransactionException, MogramException, RemoteException {
		this.service = srv;
		this.mogram = mog;
		if (service instanceof Signature) {
			Provider prv = (Provider) Accessor.get().getService((Signature) service);
			return (T) prv.service(mogram, txn);
		} else if (service instanceof Provider) {
			Task out = (Task) service.service(mogram, txn);
			return (T) out.getContext();
		} else if (service instanceof Mogram) {
			Context cxt;
			if (mogram instanceof Exertion) {
				cxt = ((Exertion) exert(mogram)).getContext();
			} else {
//				cxt = (Context) ((Model) mogram).getResult();
				cxt = (Context) mogram;
			}
			((Mogram) service).setScope(cxt);
			return (T) exert((Mogram) service);
		} else if (service instanceof NetSignature
				&& ((Signature) service).getServiceType() == sorcer.core.provider.Shell.class) {
			Provider prv = (Provider) Accessor.get().getService((Signature) service);
			return (T) ((Exertion) prv.service(mogram, txn)).getContext();
		} else if (service instanceof Par) {
			((Par)service).setScope(mogram);
			Object val =((Par)service).getValue();
			((Context)mogram).putValue(((Par)service).getName(), val);
			return (T) mogram;
		}
		return (T) service.service(mogram, txn);
	}

	public <T extends Mogram> T exec(Service srv, Mogram mog)
			throws TransactionException, MogramException, RemoteException {
		return exec(srv, mog, null);
	}

	public  <T extends Service> Object exec(T srv, Arg... args)
			throws MogramException, RemoteException {
		this.service = srv;
		if (srv instanceof NetletSignature) {
			try {
				ScriptExerter se = new ScriptExerter(System.out, null, Sorcer.getWebsterUrl(), true);
				se.readFile(new File(((NetletSignature)srv).getServiceSource()));
				return evaluate((Mogram)se.parse());
			} catch (Throwable throwable) {
				throw new MogramException(throwable);
			}
		} else if (srv instanceof Entry) {
			((Entry)service).getValue(args);
		} if (service instanceof Signature) {
			Context cxt = null;
			for(Arg arg : args) {
				if (arg instanceof Context) {
					cxt = (Context)arg;
					break;
				}
			}
			Task out = null;
			if (cxt != null) {
				try {
					Task in = task((Signature) service, cxt);
					out = ((Task) in).exert(args);
				} catch (Exception e) {
					throw new MogramException(e);
				}
				return context(out);
			} else
				throw new MogramException("Missing service context for: " + srv);
		} else if (service instanceof Exertion) {
			return value((Evaluation) service, args);
		} else if (service instanceof EntModel) {
			((Model)service).getResponse(args);
		} else if (service instanceof Context) {
			ServiceContext cxt = (ServiceContext)service;
			cxt.substitute(args);
			Signature.ReturnPath returnPath = cxt.getReturnPath();
			if (cxt instanceof EntModel) {
		          return ((Model)service).getResponse(args);
			} else if (returnPath != null){
				return cxt.getValue(returnPath.path, args);
			} else {
				throw new ExertionException("No return path in the context: "
						+ cxt.getName());
			}
		}
		return null;
	}

	public  <T extends Mogram> T exec(Signature signature, Mogram mogram)
			throws ExertionException {
		return exec(signature, mogram, null);
	}

	public  <T extends Mogram> T exec(Signature signature, Mogram mog, Transaction txn)
			throws ExertionException {
		this.service = signature;
		this.mogram = mog;
		try {
			Context cxt = null;
			if (mogram instanceof EntModel) {
				cxt = (Context) ((Model) mogram).getResponse();
			} else if (mogram instanceof ServiceContext) {
				cxt = (Context) mogram;
			}
			if (cxt != null && signature instanceof ObjectSignature) {
				ObjectTask ot = new ObjectTask(signature, cxt);
				return (T) ((Exertion)exert(ot)).getContext();
			} else if (signature instanceof NetSignature
					&& ((Signature) signature).getServiceType() == sorcer.core.provider.Shell.class) {
				Provider prv = (Provider) Accessor.get().getService(signature);
				return (T) ((Exertion) prv.service(mogram, txn)).getContext();
			} else if ((((ServiceSignature) signature).isShellRemote())
					|| ((mogram instanceof Exertion) && ((Exertion) mogram).getControlContext() != null
					&& ((ControlContext) ((Exertion) mogram).getControlContext()).isShellRemote())) {
				Exerter prv = (Exerter) Accessor.get().getService(sig(Shell.class));
				return (T) ((Exertion)prv.exert(mogram, txn)).getContext();
			} else {
				return (T) signature.service(mogram, txn);
			}
		} catch (Exception e) {
			throw new ExertionException(e);
		}
	}
}
