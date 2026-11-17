package org.sitenetsoft.quarkus.libvips.deployment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.quarkus.libvips.internal.LibvipsNative;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.nativeimage.FfmCallBuildItem;
import io.quarkus.deployment.builditem.nativeimage.FfmDowncallBuildItem;
import io.quarkus.deployment.builditem.nativeimage.FfmType;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;

/**
 * The downcall shapes registered for native image cover every C function the runtime links: each
 * {@link LibvipsNative#DOWNCALLS} descriptor, rendered here independently of the processor's own mapping, must be one
 * of {@link LibvipsProcessor#DOWNCALLS}, and nothing else is registered. Needs no libvips.
 */
class NativeDescriptorsTest {

    @Test
    void everyCFunctionHasARegisteredShape() {
        Set<String> registered = new TreeSet<>();
        for (FfmCallBuildItem d : LibvipsProcessor.DOWNCALLS) {
            registered.add(shape(d));
        }
        Set<String> linked = new TreeSet<>();
        for (LibvipsNative.Downcall d : LibvipsNative.DOWNCALLS.values()) {
            String shape = shape(d.descriptor());
            assertThat(registered).as(d.symbol() + " " + shape).contains(shape);
            linked.add(shape);
        }
        assertThat(registered).as("no shape registered that no function uses").isEqualTo(linked);
        assertThat(LibvipsProcessor.DOWNCALLS).as("one item per distinct shape").hasSize(linked.size())
                .doesNotHaveDuplicates();
        // 83 functions in 29 shapes today; a new function either reuses a shape or adds one here
        assertThat(LibvipsNative.DOWNCALLS).hasSize(83);
        assertThat(linked).hasSize(29);
    }

    @Test
    void knownSignatures() {
        // spot checks against the C headers: size_t and GType are jlong (LP64), gboolean is jint
        assertThat(shape(LibvipsNative.DOWNCALLS.get("vips_blob_copy").descriptor())).isEqualTo("void*(void*,jlong)");
        assertThat(shape(LibvipsNative.DOWNCALLS.get("g_value_set_double").descriptor()))
                .isEqualTo("void(void*,jdouble)");
        assertThat(shape(LibvipsNative.DOWNCALLS.get("g_param_value_validate").descriptor()))
                .isEqualTo("jint(void*,void*)");
        assertThat(shape(LibvipsNative.DOWNCALLS.get("g_param_spec_get_name").descriptor()))
                .isEqualTo("void*(void*)");
        assertThat(shape(LibvipsNative.DOWNCALLS.get("vips_type_find").descriptor())).isEqualTo("jlong(void*,void*)");
    }

    @Test
    void noVariadicRegistrationInV1() {
        // FfmDowncallBuildItem reports -1 when firstVariadicArg was never set
        assertThat(new FfmDowncallBuildItem(FfmType.VOID).getFirstVariadicArg()).isEqualTo(-1);
        assertThat(LibvipsNative.DOWNCALLS.values()).allSatisfy(d -> assertThat(d.firstVariadic()).isEqualTo(-1));
        assertThat(LibvipsProcessor.DOWNCALLS).allSatisfy(d -> {
            assertThat(d.getFirstVariadicArg()).isLessThan(0);
            assertThat(d.hasOptions()).isFalse();
        });
    }

    @Test
    void variadicEntryKeepsItsIndex() {
        var variadic = new LibvipsNative.Downcall("x", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), 1);
        FfmDowncallBuildItem item = LibvipsProcessor.item(variadic);
        assertThat(item.getFirstVariadicArg()).isEqualTo(1);
        assertThat(shape(item)).isEqualTo("jint(void*,void*)");
        // the same shape without the variadic index is another registration
        assertThat(LibvipsProcessor.item(new LibvipsNative.Downcall("y", variadic.descriptor(), -1)))
                .isNotEqualTo(item);
    }

    @Test
    void layoutsOutsideTheModelAreRefused() {
        assertThat(LibvipsProcessor.ffmType(ADDRESS)).isEqualTo(FfmType.ADDRESS);
        assertThat(LibvipsProcessor.ffmType(ValueLayout.JAVA_DOUBLE)).isEqualTo(FfmType.DOUBLE);
        assertThatThrownBy(() -> LibvipsProcessor.ffmType(ValueLayout.JAVA_CHAR))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LibvipsProcessor.ffmType(ValueLayout.JAVA_FLOAT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LibvipsProcessor.ffmType(MemoryLayout.structLayout(JAVA_INT)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runtimeInitializedClassesExist() throws ClassNotFoundException {
        assertThat(LibvipsProcessor.RUNTIME_INITIALIZED).containsExactlyInAnyOrder(
                "org.sitenetsoft.quarkus.libvips.internal.LibvipsNative",
                "org.sitenetsoft.quarkus.libvips.internal.LibraryLoader",
                "org.sitenetsoft.quarkus.libvips.internal.ErrorBuffer",
                "org.sitenetsoft.quarkus.libvips.internal.VipsRuntime",
                "org.sitenetsoft.quarkus.libvips.internal.NativeImage");
        for (String name : LibvipsProcessor.RUNTIME_INITIALIZED) {
            // loaded without initialising: initialising LibvipsNative needs no library, but NativeImage starts a thread
            assertThat(Class.forName(name, false, getClass().getClassLoader()).getName()).isEqualTo(name);
        }
    }

    @Test
    void buildStepProducesEveryShapeAndClass() {
        var downcalls = new java.util.ArrayList<FfmDowncallBuildItem>();
        var runtimeInit = new java.util.ArrayList<RuntimeInitializedClassBuildItem>();
        new LibvipsProcessor().nativeForeignAccess(downcalls::add);
        new LibvipsProcessor().runtimeInitialized(runtimeInit::add);
        assertThat(downcalls).containsExactlyElementsOf(LibvipsProcessor.DOWNCALLS);
        assertThat(runtimeInit).map(RuntimeInitializedClassBuildItem::getClassName)
                .containsExactlyElementsOf(LibvipsProcessor.RUNTIME_INITIALIZED);
    }

    @Test
    void runtimeInitialisationDoesNotDependOnEnabled() throws NoSuchMethodException {
        // the shapes follow quarkus.libvips.enabled; the run-time initialisation must not (DisabledTest checks a build)
        assertThat(LibvipsProcessor.class.getDeclaredMethod("nativeForeignAccess", BuildProducer.class)
                .getAnnotation(BuildStep.class).onlyIf()).containsExactly(LibvipsProcessor.IsEnabled.class);
        assertThat(LibvipsProcessor.class.getDeclaredMethod("runtimeInitialized", BuildProducer.class)
                .getAnnotation(BuildStep.class).onlyIf()).isEmpty();
    }

    /** {@code jint(void*,jlong)}, from the layouts directly (not through the processor's mapping). */
    private static String shape(FunctionDescriptor fd) {
        String ret = fd.returnLayout().map(NativeDescriptorsTest::type).orElse("void");
        return ret + "(" + String.join(",", fd.argumentLayouts().stream().map(NativeDescriptorsTest::type).toList())
                + ")";
    }

    private static String type(MemoryLayout layout) {
        if (layout instanceof AddressLayout) {
            return "void*";
        }
        if (layout instanceof ValueLayout v) {
            Class<?> carrier = v.carrier();
            if (carrier == int.class) {
                return "jint";
            } else if (carrier == long.class) {
                return "jlong";
            } else if (carrier == double.class) {
                return "jdouble";
            }
        }
        throw new AssertionError("unmodelled layout " + layout);
    }

    private static String shape(FfmCallBuildItem item) {
        return item.getReturnType() + "(" + String.join(",", item.getParameterTypes()) + ")";
    }
}
