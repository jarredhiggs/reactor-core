/*
 * Copyright (c) 2022 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.test;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.function.ThrowingConsumer;

import reactor.core.Fuseable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

/**
 * @author Simon Baslé
 */
public class TestGenerationUtils {

	public static <R, T> Stream<DynamicTest> generateScheduledWithContextInScopeTests(
		String operatorName, Flux<R> source,
		Function<Flux<R>, Flux<T>> operatorUnderTest,
		ThrowingConsumer<ScheduledWithContextInScopeFluxTest<T, Void>> dynamicTestGenerator
	) {
		return generateScheduledWithContextInScopeTests(operatorName, () -> null, threadLocalCompanion -> source,
			(src, threadLocalCompanion) -> operatorUnderTest.apply(src), dynamicTestGenerator);
	}

	public static <R, T, RES> Stream<DynamicTest> generateScheduledWithContextInScopeTests(
		String operatorName,
		Supplier<RES> resourceSupplier,
		Function<ThreadLocalCompanion<RES>, Flux<R>> sourceGenerator,
		BiFunction<Flux<R>, ThreadLocalCompanion<RES>, Flux<T>> operatorUnderTest,
		ThrowingConsumer<ScheduledWithContextInScopeFluxTest<T, RES>> dynamicTestGenerator
	) {
		String testName = operatorName + "ScheduledWithContextInScope";

		ThreadLocalCompanion<RES> mainThreadLocalCompanion = new ThreadLocalCompanion<>(resourceSupplier.get());

		//we'd like to defer the creation of the source, but we have to create one instance eagerly to detect fusion
		//this instance must not be used otherwise, since it could capture Schedulers that will be shutdown at the moment the test runs.
		Flux<R> probeSource = sourceGenerator.apply(mainThreadLocalCompanion);
		Flux<T> probeSourceAndOperator = operatorUnderTest.apply(probeSource, mainThreadLocalCompanion);
		//prepare the Supplier that will be used to actually lazily assemble the tested Flux
		Supplier<Flux<T>> mainTestedFluxSupplier = () -> {
			Flux<R> source = sourceGenerator.apply(mainThreadLocalCompanion);
			return operatorUnderTest.apply(source, mainThreadLocalCompanion);
		};

		if (probeSource instanceof Fuseable && probeSourceAndOperator instanceof Fuseable) {
			//both the source and the operator decorating the source are Fuseable. Create a hidden version.
			ThreadLocalCompanion<RES> hiddenThreadLocalCompanion = new ThreadLocalCompanion<>(resourceSupplier.get());
			Supplier<Flux<T>> hiddenTestedFluxSupplier = () -> {
				Flux<R> source = sourceGenerator.apply(hiddenThreadLocalCompanion).hide();
				return operatorUnderTest.apply(source, hiddenThreadLocalCompanion);
			};
			return DynamicTest.stream(
				Stream.of(
					new ScheduledWithContextInScopeFluxTest<>(hiddenTestedFluxSupplier, hiddenThreadLocalCompanion, testName),
					new ScheduledWithContextInScopeFluxTest<>(mainTestedFluxSupplier, mainThreadLocalCompanion, testName + "_fused")
				),
				dynamicTestGenerator
			);
		}
		//otherwise, only add one test
		return DynamicTest.stream(
			Stream.of(new ScheduledWithContextInScopeFluxTest<>(mainTestedFluxSupplier, mainThreadLocalCompanion, testName)),
			dynamicTestGenerator
		);
	}

	public static final class ThreadLocalCompanion<RES> {

		public final RES                 resource;
		public final ThreadLocal<String> threadLocal;

		ThreadLocalCompanion(RES resource) {
			this.resource = resource;
			threadLocal = ThreadLocal.withInitial(() -> "none");
		}
	}

	public static final class ScheduledWithContextInScopeFluxTest<T, RES> implements Named<ScheduledWithContextInScopeFluxTest<T, RES>> {

		private final Supplier<Flux<T>>         fluxSupplier;
		private final ThreadLocalCompanion<RES> threadLocalCompanion;
		private final String                    testName;

		public ScheduledWithContextInScopeFluxTest(Supplier<Flux<T>> fluxSupplier, ThreadLocalCompanion<RES> threadLocalCompanion, String name) {
			this.fluxSupplier = fluxSupplier;
			this.threadLocalCompanion = threadLocalCompanion;
			testName = name;
		}

		private Flux<T> setupAndGenerateSource() {
			Schedulers.onScheduleContextualHook(testName, (r, c) -> {
				final String fromContext = c.getOrDefault("key", "notFound");
				return () -> {
					threadLocalCompanion.threadLocal.set(fromContext);
					r.run();
					threadLocalCompanion.threadLocal.remove();
				};
			});

			return fluxSupplier.get();
		}

		public RES getResource() {
			return this.threadLocalCompanion.resource;
		}

		public StepVerifier.FirstStep<String> mappingTest() {
			Flux<String> transformed = setupAndGenerateSource().map(v -> "" + v + threadLocalCompanion.threadLocal.get());
			return StepVerifier.create(transformed, StepVerifierOptions.create().withInitialContext(Context.of("key", "customized")));
		}

		public StepVerifier.FirstStep<T> rawTest() {
			return StepVerifier.create(setupAndGenerateSource(), StepVerifierOptions.create().withInitialContext(Context.of("key", "customized")));
		}

		public StepVerifier.FirstStep<T> rawTest(Consumer<StepVerifierOptions> optionsModifier) {
			StepVerifierOptions options = StepVerifierOptions.create();
			optionsModifier.accept(options);
			return StepVerifier.create(setupAndGenerateSource(), options.withInitialContext(Context.of("key", "customized")));
		}

		@Override
		public String getName() {
			return testName;
		}

		@Override
		public ScheduledWithContextInScopeFluxTest<T, RES> getPayload() {
			return this;
		}
	}
}