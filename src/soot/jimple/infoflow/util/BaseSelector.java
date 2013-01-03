package soot.jimple.infoflow.util;

import soot.Unit;
import soot.Value;
import soot.jimple.ArrayRef;
import soot.jimple.internal.JCastExpr;
import soot.jimple.internal.JIfStmt;

public class BaseSelector {
	
	public static Value selectBase(Value val){
		//we taint base of array instead of array elements
		if (val instanceof ArrayRef) {
			return selectBase(((ArrayRef) val).getBase());
		}
		
		if (val instanceof JCastExpr) {
			return selectBase((((JCastExpr) val).getOpBox().getValue()));
		}
		
		return val;
	}
	
	public static Unit selectBase(Unit u){
		if (u instanceof JIfStmt) {
			return BaseSelector.selectBase(((soot.jimple.internal.JIfStmt) u).getTarget());	
		}
		return u;
		
	}

}
