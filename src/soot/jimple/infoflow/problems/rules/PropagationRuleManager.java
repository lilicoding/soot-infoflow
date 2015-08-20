package soot.jimple.infoflow.problems.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import soot.jimple.Stmt;
import soot.jimple.infoflow.InfoflowManager;
import soot.jimple.infoflow.aliasing.Aliasing;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.util.ByReferenceBoolean;

/**
 * Manager class for all propagation rules
 * 
 * @author Steven Arzt
 *
 */
public class PropagationRuleManager {
	
	protected final InfoflowManager manager;
	protected final Aliasing aliasing;
	protected final Abstraction zeroValue;
	private final List<ITaintPropagationRule> rules = new ArrayList<>();
	
	public PropagationRuleManager(InfoflowManager manager, Aliasing aliasing,
			Abstraction zeroValue) {
		this.manager = manager;
		this.aliasing = aliasing;
		this.zeroValue = zeroValue;
		
		rules.add(new SourcePropagationRule(manager, aliasing, zeroValue));
		if (manager.getConfig().getEnableExceptionTracking())
			rules.add(new ExceptionPropagationRule(manager, aliasing, zeroValue));
		if (manager.getTaintWrapper() != null)
			rules.add(new WrapperPropagationRule(manager, aliasing, zeroValue));
		if (manager.getConfig().getEnableImplicitFlows())
			rules.add(new ImplicitPropagtionRule(manager, aliasing, zeroValue));
		rules.add(new StrongUpdatePropagationRule(manager, aliasing, zeroValue));
	}
	
	/**
	 * Applies all rules to the normal flow function
	 * @param d1 The context abstraction
	 * @param source The incoming taint to propagate over the given statement
	 * @param stmt The statement to which to apply the rules
	 * @return The collection of outgoing taints
	 */
	public Set<Abstraction> applyNormalFlowFunction(Abstraction d1,
			Abstraction source, Stmt stmt) {
		return applyNormalFlowFunction(d1, source, stmt, null, null);
	}
	
	/**
	 * Applies all rules to the normal flow function
	 * @param d1 The context abstraction
	 * @param source The incoming taint to propagate over the given statement
	 * @param stmt The statement to which to apply the rules
	 * @param killSource Outgoing value for the rule to indicate whether the
	 * incoming taint abstraction shall be killed
	 * @param killAll Outgoing value that receives whether all taints shall be
	 * killed and nothing shall be propagated onwards
	 * @return The collection of outgoing taints
	 */
	public Set<Abstraction> applyNormalFlowFunction(Abstraction d1,
			Abstraction source, Stmt stmt,
			ByReferenceBoolean killSource,
			ByReferenceBoolean killAll) {
		Set<Abstraction> res = null;
		if (killSource == null)
			killSource = new ByReferenceBoolean();
		for (ITaintPropagationRule rule : rules) {
			Collection<Abstraction> ruleOut = rule.propagateNormalFlow(d1,
					source, stmt, killSource, killAll);
			if (ruleOut != null && !ruleOut.isEmpty()) {
				if (res == null)
					res = new HashSet<Abstraction>(ruleOut);
				else
					res.addAll(ruleOut);
			}
		}
		
		// Do we need to retain the source value?
		if ((killAll == null || !killAll.value) && !killSource.value) {
			if (res == null) {
				res = new HashSet<>();
				res.add(source);
			}
			else
				res.add(source);
		}
		return res;
	}
	
	/**
	 * Propagates a flow across a call site
	 * @param d1 The context abstraction
	 * @param source The abstraction to propagate over the statement
	 * @param stmt The statement at which to propagate the abstraction
	 * @param killAll Outgoing value for the rule to specify whether
	 * all taints shall be killed, i.e., nothing shall be propagated
	 * @return The new abstractions to be propagated to the next statement
	 */
	public Set<Abstraction> applyCallFlowFunction(Abstraction d1,
			Abstraction source, Stmt stmt, ByReferenceBoolean killAll) {
		Set<Abstraction> res = null;
		for (ITaintPropagationRule rule : rules) {
			Collection<Abstraction> ruleOut = rule.propagateCallFlow(
					d1, source, stmt, killAll);
			if (killAll.value)
				return null;
			if (ruleOut != null && !ruleOut.isEmpty()) {
				if (res == null)
					res = new HashSet<Abstraction>(ruleOut);
				else
					res.addAll(ruleOut);
			}
		}
		return res;
	}
	
	/**
	 * Applies all rules to the call-to-return flow function
	 * @param d1 The context abstraction
	 * @param source The incoming taint to propagate over the given statement
	 * @param stmt The statement to which to apply the rules
	 * @return The collection of outgoing taints
	 */
	public Set<Abstraction> applyCallToReturnFlowFunction(Abstraction d1,
			Abstraction source, Stmt stmt) {
		return applyCallToReturnFlowFunction(d1, source, stmt,
				new ByReferenceBoolean(), false);
	}
	
	/**
	 * Applies all rules to the call-to-return flow function
	 * @param d1 The context abstraction
	 * @param source The incoming taint to propagate over the given statement
	 * @param stmt The statement to which to apply the rules
	 * @param killSource Outgoing value for the rule to indicate whether the
	 * incoming taint abstraction shall be killed
	 * @return The collection of outgoing taints
	 */
	public Set<Abstraction> applyCallToReturnFlowFunction(Abstraction d1,
			Abstraction source, Stmt stmt, ByReferenceBoolean killSource,
			boolean noAddSource) {
		Set<Abstraction> res = null;
		for (ITaintPropagationRule rule : rules) {
			Collection<Abstraction> ruleOut = rule.propagateCallToReturnFlow(
					d1, source, stmt, killSource);
			if (ruleOut != null && !ruleOut.isEmpty()) {
				if (res == null)
					res = new HashSet<Abstraction>(ruleOut);
				else
					res.addAll(ruleOut);
			}
		}
		
		// Do we need to retain the source value?
		if (!noAddSource && !killSource.value) {
			if (res == null) {
				res = new HashSet<>();
				res.add(source);
			}
			else
				res.add(source);
		}
		return res;
	}
	
	/**
	 * Applies all rules to the return flow function
	 * @param callerD1s The context abstraction at the caller side
	 * @param source The incoming taint to propagate over the given statement
	 * @param stmt The statement to which to apply the rules
	 * @return The collection of outgoing taints
	 */
	public Set<Abstraction> applyReturnFlowFunction(
			Collection<Abstraction> callerD1s, Abstraction source, Stmt stmt) {
		Set<Abstraction> res = null;
		
		for (ITaintPropagationRule rule : rules) {
			Collection<Abstraction> ruleOut = rule.propagateReturnFlow(callerD1s,
					source, stmt);
			if (ruleOut != null && !ruleOut.isEmpty()) {
				if (res == null)
					res = new HashSet<Abstraction>(ruleOut);
				else
					res.addAll(ruleOut);
			}
		}
		return res;
	}
	
}
