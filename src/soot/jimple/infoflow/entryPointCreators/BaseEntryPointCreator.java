package soot.jimple.infoflow.entryPointCreators;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import soot.ArrayType;
import soot.BooleanType;
import soot.ByteType;
import soot.CharType;
import soot.G;
import soot.Local;
import soot.RefType;
import soot.Scene;
import soot.ShortType;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Value;
import soot.VoidType;
import soot.dava.internal.javaRep.DIntConstant;
import soot.javaToJimple.LocalGenerator;
import soot.jimple.AssignStmt;
import soot.jimple.DoubleConstant;
import soot.jimple.FloatConstant;
import soot.jimple.IntConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.LongConstant;
import soot.jimple.NewArrayExpr;
import soot.jimple.NewExpr;
import soot.jimple.NullConstant;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;

/**
 * Common base class for all entry point creators. Implementors must override the
 * createDummyMainInternal method to provide their entry point implementation.
 */
public abstract class BaseEntryPointCreator implements IEntryPointCreator {

	protected Map<String, Local> localVarsForClasses = new HashMap<String, Local>();
	private final Set<SootClass> failedClasses = new HashSet<SootClass>();
	private boolean substituteCallParams = false;
	private List<String> substituteClasses;
	
	public void setSubstituteCallParams(boolean b){
		substituteCallParams = b;
	}
	
	public void setSubstituteClasses(List<String> l){
		substituteClasses = l;
	}

	@Override
	public SootMethod createDummyMain(List<String> methods) {
		// Load the substitution classes
		if (substituteCallParams)
			for (String className : substituteClasses)
				Scene.v().forceResolve(className, SootClass.BODIES).setApplicationClass();
		
		return this.createDummyMainInternal(methods);
	}

	/**
	 * Implementors need to overwrite this method for providing the actual dummy
	 * main method
	 * @param methods The methods to be called inside the dummy main method
	 * @return The generated dummy main method
	 */
	protected abstract SootMethod createDummyMainInternal(List<String> methods);
	
	protected SootMethod createEmptyMainMethod(JimpleBody body){
		SootMethod mainMethod = new SootMethod("dummyMainMethod", new ArrayList<Type>(), VoidType.v());
		body.setMethod(mainMethod);
		mainMethod.setActiveBody(body);
		SootClass mainClass = new SootClass("dummyMainClass");
		mainClass.addMethod(mainMethod);
		// First add class to scene, then make it an application class
		// as addClass contains a call to "setLibraryClass" 
		Scene.v().addClass(mainClass);
		mainClass.setApplicationClass();
		return mainMethod;
	}
	
	protected Stmt buildMethodCall(SootMethod currentMethod, JimpleBody body, Local classLocal, LocalGenerator gen){
		assert currentMethod != null : "Current method was null";
		assert body != null : "Body was null";
		assert gen != null : "Local generator was null";
		
		InvokeExpr invokeExpr;
		if(currentMethod.getParameterCount()>0){
			List<Object> args = new LinkedList<Object>();
			for(Type tp :currentMethod.getParameterTypes()){
				args.add(getValueForType(body, gen, tp, new HashSet<SootClass>()));
			}
			if(currentMethod.isStatic()){
				invokeExpr = Jimple.v().newStaticInvokeExpr(currentMethod.makeRef(), args);
			}else{
				assert classLocal != null : "Class local method was null for non-static method call";
				if (currentMethod.isConstructor())
					invokeExpr = Jimple.v().newSpecialInvokeExpr(classLocal, currentMethod.makeRef(),args);
				else
					invokeExpr = Jimple.v().newVirtualInvokeExpr(classLocal, currentMethod.makeRef(),args);
			}
		}else{
			if(currentMethod.isStatic()){
				invokeExpr = Jimple.v().newStaticInvokeExpr(currentMethod.makeRef());
			}else{
				assert classLocal != null : "Class local method was null for non-static method call";
				if (currentMethod.isConstructor())
					invokeExpr = Jimple.v().newSpecialInvokeExpr(classLocal, currentMethod.makeRef());
				else
					invokeExpr = Jimple.v().newVirtualInvokeExpr(classLocal, currentMethod.makeRef());
			}
		}
		 
		Stmt stmt;
		if (!(currentMethod.getReturnType() instanceof VoidType)) {
			Local returnLocal = gen.generateLocal(currentMethod.getReturnType());
			stmt = Jimple.v().newAssignStmt(returnLocal, invokeExpr);
			
		} else {
			stmt = Jimple.v().newInvokeStmt(invokeExpr);
		}
		body.getUnits().add(stmt);
		return stmt;
	}

	/**
	 * Creates a value of the given type to be used as a substitution in method
	 * invocations or fields
	 * @param body The body in which to create the value
	 * @param gen The local generator
	 * @param tp The type for which to get a value
	 * @param constructionStack The set of classes we're currently constructing.
	 * Attempts to create a parameter of one of these classes will trigger
	 * the constructor loop check and the respective parameter will be
	 * substituted by null.
	 * @return The generated value, or null if no value could be generated
	 */
	private Value getValueForType(JimpleBody body,
			LocalGenerator gen, Type tp, Set<SootClass> constructionStack) {
		// Depending on the parameter type, we try to find a suitable
		// concrete substitution
		if (isSimpleType(tp.toString()))
			return getSimpleDefaultValue(tp.toString());
		else if (tp instanceof RefType) {
			SootClass classToType = ((RefType) tp).getSootClass();
			if(classToType != null){
				Value val = generateClassConstructor(classToType, body, constructionStack);
				// If we cannot create a parameter, we try a null reference.
				// Better than not creating the whole invocation...
				if(val == null)
					return NullConstant.v();
				return val;
			}
		}
		else if (tp instanceof ArrayType) {
			Value arrVal = buildArrayOfType(body, gen, (ArrayType) tp, constructionStack);
			if (arrVal == null)
				return NullConstant.v();
			System.err.println("Warning: Array paramater substituted by null");
			return arrVal;
		}
		else {
			System.err.println("Unsupported parameter type: " + tp.toString());
			return null;
		}
		throw new RuntimeException("Should never see me");
	}
	
	/**
	 * Constructs an array of the given type with a single element of this type
	 * in the given method
	 * @param body The body of the method in which to create the array
	 * @param gen The local generator
	 * @param tp The type of which to create the array
	 * @param constructionStack Set of classes currently being built to avoid
	 * constructor loops
	 * @return The local referencing the newly created array, or null if the
	 * array generation failed
	 */
	private Value buildArrayOfType(JimpleBody body, LocalGenerator gen, ArrayType tp,
			Set<SootClass> constructionStack) {
		Local local = gen.generateLocal(tp);

		// Generate a new single-element array
		NewArrayExpr newArrayExpr = Jimple.v().newNewArrayExpr(tp.getElementType(),
				IntConstant.v(1));
		AssignStmt assignArray = Jimple.v().newAssignStmt(local, newArrayExpr);
		body.getUnits().add(assignArray);
		
		// Generate a single element in the array
		AssignStmt assign = Jimple.v().newAssignStmt
				(Jimple.v().newArrayRef(local, IntConstant.v(19)),
				getValueForType(body, gen, tp.getElementType(), constructionStack));
		body.getUnits().add(assign);
		return local;
	}

	protected Local generateClassConstructor(SootClass createdClass, JimpleBody body) {
		return this.generateClassConstructor(createdClass, body, new HashSet<SootClass>());
	}
	
	private Local generateClassConstructor(SootClass createdClass, JimpleBody body,
			Set<SootClass> constructionStack) {
		if (createdClass == null || this.failedClasses.contains(createdClass))
			return null;
		
		// We cannot create instances of phantom classes as we do not have any
		// constructor information for them
		if (createdClass.isPhantom() || createdClass.isPhantomClass()) {
			System.out.println("Cannot generate constructor for phantom class " + createdClass.getName());
			return null;
		}

		LocalGenerator generator = new LocalGenerator(body);

		// if sootClass is simpleClass:
		if (isSimpleType(createdClass.toString())) {
			Local varLocal =  generator.generateLocal(getSimpleTypeFromType(createdClass.getType()));
			
			AssignStmt aStmt = Jimple.v().newAssignStmt(varLocal, getSimpleDefaultValue(createdClass.toString()));
			body.getUnits().add(aStmt);
			return varLocal;
		}
		
		boolean isInnerClass = createdClass.getName().contains("$");
		String outerClass = isInnerClass ? createdClass.getName().substring
				(0, createdClass.getName().lastIndexOf("$")) : "";
		
		// Make sure that we don't run into loops
		if (!constructionStack.add(createdClass)) {
			System.out.println("Ran into a constructor generation loop, substituting with null...");
			Local tempLocal = generator.generateLocal(RefType.v(createdClass));			
			AssignStmt assignStmt = Jimple.v().newAssignStmt(tempLocal, NullConstant.v());
			body.getUnits().add(assignStmt);
			return tempLocal;
		}
		if(createdClass.isInterface() || createdClass.isAbstract()){
			if(substituteCallParams) {
				// Find a matching implementor of the interface
				List<SootClass> classes;
				if (createdClass.isInterface())
					classes = Scene.v().getActiveHierarchy().getImplementersOf(createdClass);
				else
					classes = Scene.v().getActiveHierarchy().getSubclassesOf(createdClass);
				
				// Generate an instance of the substitution class. If we fail,
				// try the next substitution. If we don't find any possible
				// substitution, we're in trouble
				for(SootClass sClass : classes)
					if(substituteClasses.contains(sClass.toString())) {
						Local cons = generateClassConstructor(sClass, body, constructionStack);
						if (cons == null)
							continue;
						return cons;
					}
				System.err.println("Warning, cannot create valid constructor for " + createdClass +
						", because it is " + (createdClass.isInterface() ? "an interface" :
							(createdClass.isAbstract() ? "abstract" : ""))+ " and cannot substitute with subclass");
				this.failedClasses.add(createdClass);
				return null;
			}
			else{
				System.err.println("Warning, cannot create valid constructor for " + createdClass +
					", because it is " + (createdClass.isInterface() ? "an interface" :
						(createdClass.isAbstract() ? "abstract" : "")));
				//build the expression anyway:
				/* SA: why should we?
				NewExpr newExpr = Jimple.v().newNewExpr(RefType.v(createdClass));
				AssignStmt assignStmt = Jimple.v().newAssignStmt(tempLocal, newExpr);
				body.getUnits().add(assignStmt);
				*/
				this.failedClasses.add(createdClass);
				return null;
			}
		}
		else{			
			// Find a constructor we can invoke. We do this first as we don't want
			// to change anything in our method body if we cannot create a class
			// instance anyway.
			for (SootMethod currentMethod : createdClass.getMethods()) {
				if (currentMethod.isPrivate() || !currentMethod.isConstructor())
					continue;
				
				List<Value> params = new LinkedList<Value>();
				for (Type type : currentMethod.getParameterTypes()) {
					// We need to check whether we have a reference to the
					// outer class. In this case, we do not generate a new
					// instance, but use the one we already have.
					String typeName = type.toString().replaceAll("\\[\\]]", "");
					if (type instanceof RefType
							&& isInnerClass && typeName.equals(outerClass)
							&& this.localVarsForClasses.containsKey(typeName))
						params.add(this.localVarsForClasses.get(typeName));
					else
						params.add(getValueForType(body, generator, type, constructionStack));
				}

				// Build the "new" expression
				NewExpr newExpr = Jimple.v().newNewExpr(RefType.v(createdClass));
				Local tempLocal = generator.generateLocal(RefType.v(createdClass));			
				AssignStmt assignStmt = Jimple.v().newAssignStmt(tempLocal, newExpr);
				body.getUnits().add(assignStmt);		

				// Create the constructor invocation
				InvokeExpr vInvokeExpr;
				if (params.isEmpty() || params.contains(null))
					vInvokeExpr = Jimple.v().newSpecialInvokeExpr(tempLocal, currentMethod.makeRef());
				else
					vInvokeExpr = Jimple.v().newSpecialInvokeExpr(tempLocal, currentMethod.makeRef(), params);

				// Make sure to store return values
				if (!(currentMethod.getReturnType() instanceof VoidType)) { 
					Local possibleReturn = generator.generateLocal(currentMethod.getReturnType());
					AssignStmt assignStmt2 = Jimple.v().newAssignStmt(possibleReturn, vInvokeExpr);
					body.getUnits().add(assignStmt2);
				}
				else
					body.getUnits().add(Jimple.v().newInvokeStmt(vInvokeExpr));
					
				return tempLocal;
			}

			System.err.println("Could not find a suitable constructor for class "
					+ createdClass.getName());
			this.failedClasses.add(createdClass);
			return null;
		}
	}

	private Type getSimpleTypeFromType(Type type) {
		if (type.toString().equals("java.lang.String")) {
			assert type instanceof RefType;
			return RefType.v(((RefType) type).getSootClass());
		}
		if (type.toString().equals("void"))
			return soot.VoidType.v();
		if (type.toString().equals("char"))
			return soot.CharType.v();
		if (type.toString().equals("byte"))
			return soot.ByteType.v();
		if (type.toString().equals("short"))
			return soot.ShortType.v();
		if (type.toString().equals("int"))
			return soot.IntType.v();
		if (type.toString().equals("float"))
			return soot.FloatType.v();
		if (type.toString().equals("long"))
			return soot.LongType.v();
		if (type.toString().equals("double"))
			return soot.DoubleType.v();
		if (type.toString().equals("boolean"))
			return soot.BooleanType.v();
		throw new RuntimeException("Unknown simple type: " + type);
	}

	protected static boolean isSimpleType(String t) {
		if (t.equals("java.lang.String")
				|| t.equals("void")
				|| t.equals("char")
				|| t.equals("byte")
				|| t.equals("short")
				|| t.equals("int")
				|| t.equals("float")
				|| t.equals("long")
				|| t.equals("double")
				|| t.equals("boolean")) {
			return true;
		} else {
			return false;
		}
	}

	protected Value getSimpleDefaultValue(String t) {
		if (t.equals("java.lang.String"))
			return StringConstant.v("");
		if (t.equals("char"))
			return DIntConstant.v(0, CharType.v());
		if (t.equals("byte"))
			return DIntConstant.v(0, ByteType.v());
		if (t.equals("short"))
			return DIntConstant.v(0, ShortType.v());
		if (t.equals("int"))
			return IntConstant.v(0);
		if (t.equals("float"))
			return FloatConstant.v(0);
		if (t.equals("long"))
			return LongConstant.v(0);
		if (t.equals("double"))
			return DoubleConstant.v(0);
		if (t.equals("boolean"))
			return DIntConstant.v(0, BooleanType.v());

		//also for arrays etc.
		return G.v().soot_jimple_NullConstant();
	}

	/**
	 * Finds a method with the given signature in the given class or one of its
	 * super classes
	 * @param currentClass The current class in which to start the search
	 * @param subsignature The subsignature of the method to find
	 * @return The method with the given signature if it has been found,
	 * otherwise null
	 */
	protected SootMethod findMethod(SootClass currentClass, String subsignature){
		if(currentClass.declaresMethod(subsignature)){
			return currentClass.getMethod(subsignature);
		}
		if(currentClass.hasSuperclass()){
			return findMethod(currentClass.getSuperclass(), subsignature);
		}
		return null;
	}

}
