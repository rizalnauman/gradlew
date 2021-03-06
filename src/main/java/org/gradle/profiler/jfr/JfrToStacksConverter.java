package org.gradle.profiler.jfr;

import com.google.common.collect.ImmutableSet;
import org.openjdk.jmc.common.IMCFrame;
import org.openjdk.jmc.common.IMCStackTrace;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.IType;
import org.openjdk.jmc.common.item.ItemToolkit;
import org.openjdk.jmc.common.unit.BinaryPrefix;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.common.util.MemberAccessorToolkit;
import org.openjdk.jmc.flightrecorder.JfrAttributes;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;
import org.openjdk.jmc.flightrecorder.stacktrace.FrameSeparator;
import org.openjdk.jmc.flightrecorder.stacktrace.FrameSeparator.FrameCategorization;
import org.openjdk.jmc.flightrecorder.stacktrace.StacktraceFormatToolkit;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Converts JFR recordings to the collapsed stacks format used by the FlameGraph tool.
 */
public class JfrToStacksConverter {
    public void convertToStacks(List<IItemCollection> recordings, File targetFile, Options options) {
        Map<String, Long> foldedStacks = foldStacks(recordings, options);
        writeFoldedStacks(foldedStacks, targetFile);
    }

    private Map<String, Long> foldStacks(List<IItemCollection> recordings, Options options) {
        StackFolder folder = new StackFolder(options);
        recordings.stream()
            .flatMap(recording -> StreamSupport.stream(recording.spliterator(), false))
            .flatMap(eventStream -> StreamSupport.stream(eventStream.spliterator(), false))
            .filter(options.eventType::matches)
            .filter(event -> getStackTrace(event) != null)
            .forEach(folder);
        return folder.getFoldedStacks();
    }

    private void writeFoldedStacks(Map<String, Long> foldedStacks, File targetFile) {
        targetFile.getParentFile().mkdirs();
        try (BufferedWriter writer = Files.newBufferedWriter(targetFile.toPath(), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, Long> entry : foldedStacks.entrySet()) {
                writer.write(String.format("%s %d%n", entry.getKey(), entry.getValue()));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static IMCStackTrace getStackTrace(IItem event) {
        return ItemToolkit.getItemType(event).getAccessor(JfrAttributes.EVENT_STACKTRACE.getKey()).getMember(event);
    }

    private static class StackFolder implements Consumer<IItem> {
        private final Options options;
        private final Map<String, Long> foldedStacks = new LinkedHashMap<>();

        public StackFolder(Options options) {
            this.options = options;
        }

        @Override
        public void accept(IItem event) {
            String stack = toStack(event);
            Long sum = foldedStacks.get(stack);
            long value = getValue(event);
            if (sum == null) {
                sum = value;
            } else {
                sum += value;
            }
            foldedStacks.put(stack, sum);
        }

        private String toStack(IItem event) {
            IMCStackTrace stackTrace = getStackTrace(event);
            List<IMCFrame> reverseStacks = new ArrayList<>(stackTrace.getFrames());
            Collections.reverse(reverseStacks);
            return reverseStacks.stream()
                .map(this::frameName)
                .collect(Collectors.joining(";"));
        }

        private String frameName(IMCFrame frame) {
            String frameName = StacktraceFormatToolkit.formatFrame(
                frame,
                new FrameSeparator(options.isShowLineNumbers() ? FrameCategorization.LINE : FrameCategorization.METHOD, false),
                false,
                false,
                true,
                true,
                options.isShowArguments(),
                true
            );
            return frame.getType() == IMCFrame.Type.UNKNOWN
                ? frameName
                : frameName + "_[j]";
        }

        private long getValue(IItem event) {
            return options.getEventType().getValue(event);
        }

        public Map<String, Long> getFoldedStacks() {
            return foldedStacks;
        }
    }

    public static class Options {
        private final EventType eventType;
        private final boolean showArguments;
        private final boolean showLineNumbers;

        public Options(EventType eventType, boolean showArguments, boolean showLineNumbers) {
            this.eventType = eventType;
            this.showArguments = showArguments;
            this.showLineNumbers = showLineNumbers;
        }

        public EventType getEventType() {
            return eventType;
        }

        public boolean isShowArguments() {
            return showArguments;
        }

        public boolean isShowLineNumbers() {
            return showLineNumbers;
        }
    }

    public enum EventType {

        CPU("cpu", "CPU", "samples", ValueField.COUNT, "Method Profiling Sample", "Method Profiling Sample Native"),
        ALLOCATION("allocation", "Allocation size", "kB", ValueField.ALLOCATION_SIZE, "Allocation in new TLAB", "Allocation outside TLAB"),
        MONITOR_BLOCKED("monitor-blocked", "Java Monitor Blocked", "ns", ValueField.DURATION, "Java Monitor Blocked", "Java Thread Park"),
        IO("io", "File and Socket IO", "ns", ValueField.DURATION, "File Read", "File Write", "Socket Read", "Socket Write");

        private final String id;
        private final String displayName;
        private final String unitOfMeasure;
        private final ValueField valueField;
        private final Set<String> eventNames;

        EventType(String id, String displayName, String unitOfMeasure, ValueField valueField, String... eventNames) {
            this.id = id;
            this.displayName = displayName;
            this.unitOfMeasure = unitOfMeasure;
            this.eventNames = ImmutableSet.copyOf(eventNames);
            this.valueField = valueField;
        }

        public boolean matches(IItem event) {
            return eventNames.contains(event.getType().getName());
        }

        public long getValue(IItem event) {
            return valueField.getValue(event);
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getUnitOfMeasure() {
            return unitOfMeasure;
        }

        private enum ValueField {
            COUNT {
                @Override
                public long getValue(IItem event) {
                    return 1;
                }
            },
            DURATION {
                @Override
                public long getValue(IItem event) {
                    IType<IItem> itemType = ItemToolkit.getItemType(event);
                    IMemberAccessor<IQuantity, IItem> duration = itemType.getAccessor(JfrAttributes.DURATION.getKey());
                    if (duration == null) {
                        IMemberAccessor<IQuantity, IItem> startTime = itemType.getAccessor(JfrAttributes.START_TIME.getKey());
                        IMemberAccessor<IQuantity, IItem> endTime = itemType.getAccessor(JfrAttributes.END_TIME.getKey());
                        duration = MemberAccessorToolkit.difference(endTime, startTime);
                    }
                    return duration.getMember(event).in(UnitLookup.NANOSECOND).longValue();
                }
            },
            ALLOCATION_SIZE {
                @Override
                public long getValue(IItem event) {
                    IType<IItem> itemType = ItemToolkit.getItemType(event);
                    IMemberAccessor<IQuantity, IItem> accessor = itemType.getAccessor(JdkAttributes.TLAB_SIZE.getKey());
                    if (accessor == null) {
                        accessor = itemType.getAccessor(JdkAttributes.ALLOCATION_SIZE.getKey());
                    }
                    return accessor.getMember(event)
                        .in(UnitLookup.MEMORY.getUnit(BinaryPrefix.KIBI))
                        .longValue();
                }
            };

            public abstract long getValue(IItem event);
        }
    }
}
