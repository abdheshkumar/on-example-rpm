package no.officenet.example.rpm.support.infrastructure.validation;

import net.sf.oval.Check;
import net.sf.oval.CheckExclusion;
import net.sf.oval.collection.CollectionFactory;
import net.sf.oval.configuration.CheckInitializationListener;
import net.sf.oval.configuration.Configurer;
import net.sf.oval.configuration.annotation.AnnotationCheck;
import net.sf.oval.configuration.annotation.AnnotationCheckExclusion;
import net.sf.oval.configuration.annotation.Constraint;
import net.sf.oval.configuration.annotation.Constraints;
import net.sf.oval.configuration.annotation.Exclusion;
import net.sf.oval.configuration.pojo.elements.ClassConfiguration;
import net.sf.oval.configuration.pojo.elements.ConstraintSetConfiguration;
import net.sf.oval.configuration.pojo.elements.ConstructorConfiguration;
import net.sf.oval.configuration.pojo.elements.FieldConfiguration;
import net.sf.oval.configuration.pojo.elements.MethodConfiguration;
import net.sf.oval.configuration.pojo.elements.MethodPostExecutionConfiguration;
import net.sf.oval.configuration.pojo.elements.MethodPreExecutionConfiguration;
import net.sf.oval.configuration.pojo.elements.MethodReturnValueConfiguration;
import net.sf.oval.configuration.pojo.elements.ObjectConfiguration;
import net.sf.oval.configuration.pojo.elements.ParameterConfiguration;
import net.sf.oval.exception.OValException;
import net.sf.oval.exception.ReflectionException;
import net.sf.oval.guard.Guarded;
import net.sf.oval.guard.Post;
import net.sf.oval.guard.PostCheck;
import net.sf.oval.guard.PostValidateThis;
import net.sf.oval.guard.Pre;
import net.sf.oval.guard.PreCheck;
import net.sf.oval.guard.PreValidateThis;
import net.sf.oval.internal.util.Assert;
import net.sf.oval.internal.util.LinkedSet;
import net.sf.oval.internal.util.ReflectionUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static net.sf.oval.Validator.getCollectionFactory;

/**
 * The only difference between this and {@link net.sf.oval.configuration.annotation.AnnotationsConfigurer}
 * is that this implementation assues IsInvariant=true for all getter-based annotations
 */
public class RpmAnnotationsConfigurer implements Configurer
{
	protected final Set<CheckInitializationListener> listeners = new LinkedSet<CheckInitializationListener>(2);

	private List<ParameterConfiguration> _createParameterConfiguration(final Annotation[][] paramAnnotations,
			final Class< ? >[] parameterTypes)
	{
		final CollectionFactory cf = getCollectionFactory();

		final List<ParameterConfiguration> paramCfg = cf.createList(paramAnnotations.length);

		List<Check> paramChecks = cf.createList(2);
		List<CheckExclusion> paramCheckExclusions = cf.createList(2);

		// loop over all parameters of the current constructor
		for (int i = 0; i < paramAnnotations.length; i++)
		{
			// loop over all annotations of the current constructor parameter
			for (final Annotation annotation : paramAnnotations[i])
				// check if the current annotation is a constraint annotation
				if (annotation.annotationType().isAnnotationPresent(Constraint.class))
					paramChecks.add(initializeCheck(annotation));
				else if (annotation.annotationType().isAnnotationPresent(Constraints.class))
					initializeChecks(annotation, paramChecks);
				else if (annotation.annotationType().isAnnotationPresent(Exclusion.class))
					paramCheckExclusions.add(initializeExclusion(annotation));

			final ParameterConfiguration pc = new ParameterConfiguration();
			paramCfg.add(pc);
			pc.type = parameterTypes[i];
			if (paramChecks.size() > 0)
			{
				pc.checks = paramChecks;
				paramChecks = cf.createList(2); // create a new list for the next parameter having checks
			}
			if (paramCheckExclusions.size() > 0)
			{
				pc.checkExclusions = paramCheckExclusions;
				paramCheckExclusions = cf.createList(2); // create a new list for the next parameter having check exclusions
			}
		}
		return paramCfg;
	}

	public boolean addCheckInitializationListener(final CheckInitializationListener listener)
	{
		Assert.argumentNotNull("listener", "[listener] must not be null");
		return listeners.add(listener);
	}

	protected void configureConstructorParameterChecks(final ClassConfiguration classCfg)
	{
		final CollectionFactory cf = getCollectionFactory();

		for (final Constructor< ? > ctor : classCfg.type.getDeclaredConstructors())
		{
			final List<ParameterConfiguration> paramCfg = _createParameterConfiguration(ctor.getParameterAnnotations(),
					ctor.getParameterTypes());

			final boolean postValidateThis = ctor.isAnnotationPresent(PostValidateThis.class);

			if (paramCfg.size() > 0 || postValidateThis)
			{
				if (classCfg.constructorConfigurations == null) classCfg.constructorConfigurations = cf.createSet(2);

				final ConstructorConfiguration cc = new ConstructorConfiguration();
				cc.parameterConfigurations = paramCfg;
				cc.postCheckInvariants = postValidateThis;
				classCfg.constructorConfigurations.add(cc);
			}
		}
	}

	protected void configureFieldChecks(final ClassConfiguration classCfg)
	{
		final CollectionFactory cf = getCollectionFactory();

		List<Check> checks = cf.createList(2);

		for (final Field field : classCfg.type.getDeclaredFields())
		{
			// loop over all annotations of the current field
			for (final Annotation annotation : field.getAnnotations())
				// check if the current annotation is a constraint annotation
				if (annotation.annotationType().isAnnotationPresent(Constraint.class))
					checks.add(initializeCheck(annotation));
				else if (annotation.annotationType().isAnnotationPresent(Constraints.class))
					initializeChecks(annotation, checks);

			if (checks.size() > 0)
			{
				if (classCfg.fieldConfigurations == null) classCfg.fieldConfigurations = cf.createSet(2);

				final FieldConfiguration fc = new FieldConfiguration();
				fc.name = field.getName();
				fc.checks = checks;
				classCfg.fieldConfigurations.add(fc);
				checks = cf.createList(2); // create a new list for the next field with checks
			}
		}
	}

	/**
	 * configure method return value and parameter checks
	 */
	protected void configureMethodChecks(final ClassConfiguration classCfg)
	{
		final CollectionFactory cf = getCollectionFactory();

		List<Check> returnValueChecks = cf.createList(2);
		List<PreCheck> preChecks = cf.createList(2);
		List<PostCheck> postChecks = cf.createList(2);

		for (final Method method : classCfg.type.getDeclaredMethods())
		{
			/*
			 * determine method return value checks and method pre/post
			 * conditions
			 */
			boolean preValidateThis = false;
			boolean postValidateThis = false;

			// loop over all annotations
			for (final Annotation annotation : ReflectionUtils.getAnnotations(method,
				Boolean.TRUE.equals(classCfg.inspectInterfaces)))
				if (annotation instanceof Pre)
				{
					final PreCheck pc = new PreCheck();
					pc.configure((Pre) annotation);
					preChecks.add(pc);
				}
				else if (annotation instanceof PreValidateThis)
					preValidateThis = true;
				else if (annotation instanceof Post)
				{
					final PostCheck pc = new PostCheck();
					pc.configure((Post) annotation);
					postChecks.add(pc);
				}
				else if (annotation instanceof PostValidateThis)
					postValidateThis = true;
				else if (annotation.annotationType().isAnnotationPresent(Constraint.class))
					returnValueChecks.add(initializeCheck(annotation));
				else if (annotation.annotationType().isAnnotationPresent(Constraints.class))
					initializeChecks(annotation, returnValueChecks);

			/*
			 * determine parameter checks
			 */
			final List<ParameterConfiguration> paramCfg = _createParameterConfiguration(
					ReflectionUtils.getParameterAnnotations(method, Boolean.TRUE.equals(classCfg.inspectInterfaces)),
					method.getParameterTypes());

			// check if anything has been configured for this method at all
			if (paramCfg.size() > 0 || returnValueChecks.size() > 0 || preChecks.size() > 0 || postChecks.size() > 0
					|| preValidateThis || postValidateThis)
			{
				if (classCfg.methodConfigurations == null) classCfg.methodConfigurations = cf.createSet(2);

				final MethodConfiguration mc = new MethodConfiguration();
				mc.name = method.getName();
				mc.parameterConfigurations = paramCfg;
				// Note: Test for @Constraint (all validation-constraint annotations) instead of @IsInvariant
				mc.isInvariant = hasConstraintAnnotation(classCfg, method);
				mc.preCheckInvariants = preValidateThis;
				mc.postCheckInvariants = postValidateThis;
				if (returnValueChecks.size() > 0)
				{
					mc.returnValueConfiguration = new MethodReturnValueConfiguration();
					mc.returnValueConfiguration.checks = returnValueChecks;
					returnValueChecks = cf.createList(2); // create a new list for the next method having return value checks
				}
				if (preChecks.size() > 0)
				{
					mc.preExecutionConfiguration = new MethodPreExecutionConfiguration();
					mc.preExecutionConfiguration.checks = preChecks;
					preChecks = cf.createList(2); // create a new list for the next method having pre checks
				}
				if (postChecks.size() > 0)
				{
					mc.postExecutionConfiguration = new MethodPostExecutionConfiguration();
					mc.postExecutionConfiguration.checks = postChecks;
					postChecks = cf.createList(2); // create a new list for the next method having post checks
				}
				classCfg.methodConfigurations.add(mc);
			}
		}
	}

	private boolean hasConstraintAnnotation(ClassConfiguration classCfg, Method method) {
		for (Annotation annotation : method.getAnnotations()) {
			if (annotation.annotationType().isAnnotationPresent(Constraint.class)) {
				return true;
			}
		}
		return false;
	}

	protected void configureObjectLevelChecks(final ClassConfiguration classCfg)
	{
		final List<Check> checks = getCollectionFactory().createList(2);

		for (final Annotation annotation : ReflectionUtils.getAnnotations(classCfg.type,
				Boolean.TRUE.equals(classCfg.inspectInterfaces)))
			// check if the current annotation is a constraint annotation
			if (annotation.annotationType().isAnnotationPresent(Constraint.class))
				checks.add(initializeCheck(annotation));
			else if (annotation.annotationType().isAnnotationPresent(Constraints.class))
				initializeChecks(annotation, checks);

		if (checks.size() > 0)
		{
			classCfg.objectConfiguration = new ObjectConfiguration();
			classCfg.objectConfiguration.checks = checks;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public ClassConfiguration getClassConfiguration(final Class< ? > clazz)
	{
		final ClassConfiguration classCfg = new ClassConfiguration();
		classCfg.type = clazz;

		final Guarded guarded = clazz.getAnnotation(Guarded.class);

		if (guarded == null)
		{
			classCfg.applyFieldConstraintsToConstructors = false;
			classCfg.applyFieldConstraintsToSetters = false;
			classCfg.assertParametersNotNull = false;
			classCfg.checkInvariants = false;
			classCfg.inspectInterfaces = false;
		}
		else
		{
			classCfg.applyFieldConstraintsToConstructors = guarded.applyFieldConstraintsToConstructors();
			classCfg.applyFieldConstraintsToSetters = guarded.applyFieldConstraintsToSetters();
			classCfg.assertParametersNotNull = guarded.assertParametersNotNull();
			classCfg.checkInvariants = guarded.checkInvariants();
			classCfg.inspectInterfaces = guarded.inspectInterfaces();
		}

		configureObjectLevelChecks(classCfg);
		configureFieldChecks(classCfg);
		configureConstructorParameterChecks(classCfg);
		configureMethodChecks(classCfg);

		return classCfg;
	}

	/**
	 * {@inheritDoc}
	 */
	public ConstraintSetConfiguration getConstraintSetConfiguration(final String constraintSetId)
	{
		return null;
	}

	protected <ConstraintAnnotation extends Annotation> AnnotationCheck<ConstraintAnnotation> initializeCheck(
			final ConstraintAnnotation constraintAnnotation) throws ReflectionException
	{
		assert constraintAnnotation != null;

		final Constraint constraint = constraintAnnotation.annotationType().getAnnotation(Constraint.class);

		// determine the check class
		@SuppressWarnings("unchecked")
		final Class<AnnotationCheck<ConstraintAnnotation>> checkClass = (Class<AnnotationCheck<ConstraintAnnotation>>) constraint
				.checkWith();

		// instantiate the appropriate check for the found constraint
		final AnnotationCheck<ConstraintAnnotation> check = newCheckInstance(checkClass);
		check.configure(constraintAnnotation);

		for (final CheckInitializationListener listener : listeners)
			listener.onCheckInitialized(check);
		return check;
	}

	protected <ConstraintsAnnotation extends Annotation> void initializeChecks(
			final ConstraintsAnnotation constraintsAnnotation, final List<Check> checks) throws ReflectionException
	{
		try
		{
			final Method getValue = constraintsAnnotation.annotationType().getDeclaredMethod("value",
					(Class< ? >[]) null);
			final Object[] constraintAnnotations = (Object[]) getValue.invoke(constraintsAnnotation, (Object[]) null);
			for (final Object ca : constraintAnnotations)
				checks.add(initializeCheck((Annotation) ca));
		}
		catch (final ReflectionException ex)
		{
			throw ex;
		}
		catch (final Exception ex)
		{
			throw new ReflectionException("Cannot initialize constraint check "
					+ constraintsAnnotation.annotationType().getName(), ex);
		}
	}

	protected <ExclusionAnnotation extends Annotation> AnnotationCheckExclusion<ExclusionAnnotation> initializeExclusion(
			final ExclusionAnnotation exclusionAnnotation) throws ReflectionException
	{
		assert exclusionAnnotation != null;

		final Exclusion constraint = exclusionAnnotation.annotationType().getAnnotation(Exclusion.class);

		// determine the check class
		final Class< ? > exclusionClass = constraint.excludeWith();

		try
		{
			// instantiate the appropriate exclusion for the found annotation
			@SuppressWarnings("unchecked")
			final AnnotationCheckExclusion<ExclusionAnnotation> exclusion = (AnnotationCheckExclusion<ExclusionAnnotation>) exclusionClass
					.newInstance();
			exclusion.configure(exclusionAnnotation);
			return exclusion;
		}
		catch (final Exception ex)
		{
			throw new ReflectionException("Cannot initialize constraint exclusion " + exclusionClass.getName(), ex);
		}
	}

	/**
	 * @return a new instance of the given constraint check implementation class
	 */
	protected <ConstraintAnnotation extends Annotation> AnnotationCheck<ConstraintAnnotation> newCheckInstance(
			final Class<AnnotationCheck<ConstraintAnnotation>> checkClass) throws OValException
	{
		try
		{
			return checkClass.newInstance();
		}
		catch (final InstantiationException ex)
		{
			throw new ReflectionException("Cannot initialize constraint check " + checkClass.getName(), ex);
		}
		catch (final IllegalAccessException ex)
		{
			throw new ReflectionException("Cannot initialize constraint check " + checkClass.getName(), ex);
		}
	}

	public boolean removeCheckInitializationListener(final CheckInitializationListener listener)
	{
		return listeners.remove(listener);
	}
}
