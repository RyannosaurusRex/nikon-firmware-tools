package com.nikonhacker.emu;

import com.nikonhacker.Constants;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class MasterClock implements Runnable {

    public static final long PS_PER_MS = 1_000_000_000;
    public static final long PS_PER_SEC = 1_000_000_000_000L;

    private DecimalFormat milliSecondFormatter = new DecimalFormat("0000.000000000");

    private ClockableCallbackHandler[] clockableCallbackHandlers;
    /**
     * All objects to "clock", encapsulated in an internal class to store their counter value and threshold
     */
    private final List<ClockableEntry> entries = new CopyOnWriteArrayList<>();

    private boolean syncPlay = false;

    private boolean running = false;

    /**
     * The total elapsed time since the start of the MasterClock, in picoseconds (e-12)
     * MAX_LONG being (2e63 - 1) = 9.22e18, it will overflow after 9223372 seconds,
     * which is 2562 hours or more than 100 emulated days.
     */
    private long totalElapsedTimePs;

    /**
     * The duration represented by one MasterClock "tick", in picoseconds (1e-12 seconds)
     */
    private long masterClockTickDurationPs;

    /**
     * A temp flag to indicate that the computing of intervals based on frequencies must be performed again
     * (due to a change in the list of Clockable, or a frequency change)
     */
    private boolean rescheduleRequested;

    /**
     * Optimized list of steps to execute
     */
    private List<ClockExecutionStep> steps;

    public MasterClock() {
    }

    public void requestResheduling() {
        rescheduleRequested = true;
    }

    /**
     * Add a clockable object.
     * @param clockable the object to wake up repeatedly
     * @param clockableCallbackHandlerChip the chip to call on exit or Exception
     */
    public synchronized void add(Clockable clockable, int clockableCallbackHandlerChip, boolean enabled, boolean precise) {
        //System.err.println("Adding " + clockable.getClass().getSimpleName());
        // Check if already present
        boolean found = false;
        for (ClockableEntry entry : entries) {
            if (entry.clockable == clockable) {
                // make sure it is enabled
                entry.enabled = true;
                found = true;
                break;
            }
        }
        if (!found) {
            entries.add(new ClockableEntry(clockable, clockableCallbackHandlerChip, enabled, precise));
        }
        requestResheduling();
    }

    /**
     * Simpler version
     * @param clockable
     */
    public void add(Clockable clockable) {
        add(clockable, -1, true, true);
    }

    /**
     * Removes a clockable object.
     * @param clockable the object to remove
     */
    public synchronized void remove(Clockable clockable) {
        for (int i = 0; i < entries.size(); i++) {
            ClockableEntry entry = entries.get(i);
            if (entry.clockable == clockable) {
                //System.err.println("Removing " + entry.clockable.getClass().getSimpleName());
                entries.remove(entry);
                break;
            }
        }
        requestResheduling();
    }

    private void prepareSchedule() {
        // Reset indicator, if set
        rescheduleRequested = false;

        // Determine least common multiple of all frequencies
        long leastCommonMultipleFrequency = 1;
        int maxUnpreciseFrequency = 1;

        // Precompute all frequencies
        Map<ClockableEntry,Integer> entryFrequencies = new HashMap<>();

        // Compute least common multiple frequency
        for (ClockableEntry entry : entries) {
            final int frequencyHz = entry.clockable.getFrequencyHz();
            entryFrequencies.put(entry, frequencyHz);

            if (frequencyHz > 0) {
                // skip unprecise entrys like serial, etc
                if (entry.isPrecise) {
                    leastCommonMultipleFrequency = longLCM(frequencyHz, leastCommonMultipleFrequency);
                } else {
                    maxUnpreciseFrequency = frequencyHz;
                }
                entry.isFrequencyZero = false;
            }
            else {
                entry.isFrequencyZero = true;
            }
        }
        // not likely, but possible: unprecise items accept drift of 25%
        if (leastCommonMultipleFrequency < (maxUnpreciseFrequency<<2)) {
            // include unprecise entries also in calculation
            for (ClockableEntry entry : entries) {
                if (!entry.isPrecise) {
                    int frequencyHz = entryFrequencies.get(entry);
                    leastCommonMultipleFrequency = longLCM(frequencyHz, leastCommonMultipleFrequency);
                }
            }
        }

        // OK. Now set each counter threshold to the value of lcm/freq
        int leastCommonCounterThreshold = 1;
        for (ClockableEntry entry : entries) {
            if (!entry.isFrequencyZero) {
                int newThreshold = (int) (leastCommonMultipleFrequency / entryFrequencies.get(entry));
                if (entry.counterThreshold != 0) {
                    // Adjust value according to new threshold
                    entry.counterValue = entry.counterValue * newThreshold / entry.counterThreshold;
                }
                // Set new threshold
                entry.counterThreshold = newThreshold;
                leastCommonCounterThreshold = intLCM(newThreshold, leastCommonCounterThreshold);
            }
        }
        // TODO
        // coderat: Problem fixed for serial port transfer time at low baudrates (96 KBps or 9600 Bps) - they are ignored during
        // calculations by setting isPrecise=false
        //
        // BUT in general cases with two different Clockables with extremly low frequency and extremly high frequency
        // still exist, yielding huge treshholds and Emulator runing slow or virtually hanging.
        if (leastCommonCounterThreshold>20000)
            System.out.println("WARNING: MasterClock calculations take too long("+ leastCommonCounterThreshold +"), because frequencies are very different. Some timers will be unprecise !!!");

        masterClockTickDurationPs = PS_PER_SEC/leastCommonMultipleFrequency;

        // DEBUG
//        System.err.println("MasterClock reconfigured with one tick=" + masterClockTickDurationPs + "ps, with the following entries:");
//        for (ClockableEntry entry : entries) {
//            System.err.println("  " + (entry.enabled ? "ON: " : "OFF:") + entry.clockable.toString() + " @" + entryFrequencies.get(entry) + "Hz, every " + entry.counterThreshold + " ticks");
//            System.err.print("  Pattern: ");
//            for(int stepNumber = 0; stepNumber < leastCommonCounterThreshold; stepNumber++) {
//                if (stepNumber % entry.counterThreshold == 0) {
//                    System.err.print("X");
//                }
//                else {
//                    System.err.print("-");
//                }
//            }
//            System.err.println();
//        }
//        System.err.println("---------------------------------------");

        // This is the start of the new "optimized" structure
        // This one gets rid of the entries that don't have to run at each step, and of the useless steps
        steps = new ArrayList<>();
        for(int stepNumber = 0; stepNumber < leastCommonCounterThreshold; stepNumber++) {
            ClockExecutionStep step = new ClockExecutionStep();
            // Collect all entries that should run at this step
            for (ClockableEntry entry : entries) {
                if (stepNumber % entry.counterThreshold == 0) {
                    step.entriesToRunAtThisStep.add(entry);
                }
            }
            if (step.entriesToRunAtThisStep.isEmpty()) {
                // Nothing has to run here, so drop this step, but add one "tick" to the previous step's duration
                steps.get(steps.size() - 1).stepDurationPs += masterClockTickDurationPs;
            }
            else {
                // Some entries have to run at this step, so keep it and give it the duration of one "tick"
                step.stepDurationPs = masterClockTickDurationPs;
                steps.add(step);
            }
        }

        // DEBUG
//        System.err.println("List of the steps:");
//        for (ClockExecutionStep step : steps) {
//            System.err.println("During " + step.stepDurationPs + "ps:");
//            for (ClockableEntry clockableEntry : step.entriesToRunAtThisStep) {
//                System.err.println("  " + clockableEntry.clockable.toString());
//            }
//        }
    }

    public void setSyncPlay(boolean syncPlay) {
        this.syncPlay = syncPlay;
    }

    /**
     * This is the normal way to start the MasterClock asynchronously.
     * Does nothing if the clock is not already running.
     */
    public synchronized void start() {
        if (!running) {
            running = true;
            new Thread(this).start();
        }
    }

    /**
     * This is the way to run the clock synchronously. Normally only called internally.
     * Use start() instead to start the clock.
     * Note: this is the optimized version that only executes useful entries of useful steps
     */
    public void run() {
        List<ClockableEntry> entriesToDisable = new ArrayList<>();
        int stepNumber = 0;
        ClockExecutionStep step;
        // Infinite loop
        while (running) {
            if (rescheduleRequested) {
                prepareSchedule();
            }
            // Iterate on all steps
            for (stepNumber = 0; stepNumber < steps.size(); stepNumber++) {
                step = steps.get(stepNumber);
                // For each step, execute all entries that should run at this step
                for (ClockableEntry currentEntry : step.entriesToRunAtThisStep) {
                    // TODO get rid of the isFrequencyZero by recomputing useful steps at each frequency change
                    if (currentEntry.enabled && !currentEntry.isFrequencyZero) {
                        // If it's enabled. Call its onClockTick() method
                        try {
                            Object result = currentEntry.clockable.onClockTick();
                            if (result != null) {
                                // A non-null result means this entry shouldn't run anymore
                                entriesToDisable.add(currentEntry);
                                // Warn the callback method
                                if (currentEntry.clockableCallbackHandlerChip >=0) {
                                    clockableCallbackHandlers[currentEntry.clockableCallbackHandlerChip].onNormalExit(result);
                                }
                            }
                        }
                        catch (Exception e) {
                            // In case of exception this entry shouldn't run anymore
                            entriesToDisable.add(currentEntry);
                            // Warn the callback method
                            if (currentEntry.clockableCallbackHandlerChip >=0) {
                                clockableCallbackHandlers[currentEntry.clockableCallbackHandlerChip].onException(e);
                            }
                        }

                    }
                }
                // Check if some entries need to be disabled
                if (!entriesToDisable.isEmpty()) {
                    for (ClockableEntry entryToDisable : entriesToDisable) {
                        disableEntry(entryToDisable);
                    }
                    entriesToDisable.clear();

                    // Check if all entries are disabled
                    if (allEntriesDisabled()) {
                        // All entries are now disabled. Stop clock
                        running = false;
                        break;
                    }
                }
                // Increment elapsed time
                totalElapsedTimePs += step.stepDurationPs;

                if (rescheduleRequested) {
                    // To perform reschedule, we need to exit the loop on steps
                    // Note that this is not really transparent as it will "reset" the count of the steps...
                    break;
                }
            }
        }

        // If we got here, one entry at least was just disabled and caused the clock to stop.
        // Before we exit, let's rotate the list so that when the clock restarts, it resumes exactly where it left off
        // To do so, the next entry to run will be rotated to the start
        Collections.rotate(steps, -1 - stepNumber);
    }

    public void enableClockable(Clockable clockable) {
        for (ClockableEntry candidateEntry : entries) {
            if (candidateEntry.clockable == clockable) {
                candidateEntry.enabled = true;
                if (candidateEntry.clockable instanceof Emulator) {
                    setLinkedEntriesEnabled(candidateEntry.clockable.getChip(), true);
                }
                break;
            }
        }
    }

    /**
     * Disable the given entry, and if it's an emulator, all timers linked to it,
     * plus, if syncPlay, the other emulator and its timers
     * @param entryToDisable
     */
    private void disableEntry(ClockableEntry entryToDisable) {
        // Actually disable that entry
        //System.err.println("Disabling " + currentEntry.clockable.getClass().getSimpleName());
        entryToDisable.enabled = false;
        if (entryToDisable.clockable instanceof Emulator) {
            setLinkedEntriesEnabled(entryToDisable.clockable.getChip(), false);
            if (syncPlay) {
                // Warn all other emulators that they are forced to stop, and disable them
                for (ClockableEntry candidateEntry : entries) {
                    if (candidateEntry.enabled && candidateEntry.clockable instanceof Emulator) {
                        //System.err.println("Calling onNormalExit() on callback for " + candidateEntry.clockable.getClass().getSimpleName());
                        if (candidateEntry.clockableCallbackHandlerChip >= 0) {
                            clockableCallbackHandlers[candidateEntry.clockableCallbackHandlerChip].onNormalExit("Sync stop due to " + entryToDisable.clockable.getClass().getSimpleName());
                        }
                        //System.err.println("Disabling " + candidateEntry.clockable.getClass().getSimpleName());
                        candidateEntry.enabled = false;
                        setLinkedEntriesEnabled(candidateEntry.clockable.getChip(), false);
                    }
                }
                // Stop clock
                //System.err.println("Requesting clock stop");
            }
        }
    }

    private void setLinkedEntriesEnabled(int chip, boolean enabled) {
        for (ClockableEntry candidateEntry : entries) {
            if ((candidateEntry.enabled != enabled) && (candidateEntry.clockable.getChip() == chip)) {
                if (!enabled && candidateEntry.clockableCallbackHandlerChip >= 0) {
                    //System.err.println("Calling onNormalExit() on callback for " + candidateEntry.clockable.getClass().getSimpleName());
                    clockableCallbackHandlers[candidateEntry.clockableCallbackHandlerChip].onNormalExit("Sync stop due to chip " + Constants.CHIP_LABEL[chip] + " stopping.");
                }
                //System.err.println((enabled?"Enabling ":"Disabling ") + candidateEntry.clockable.getClass().getSimpleName());
                candidateEntry.enabled = enabled;
            }
        }
    }

    private boolean allEntriesDisabled() {
        boolean allEntriesDisabled = true;
        for (ClockableEntry clockableEntry : entries) {
            if (clockableEntry.enabled) {
                allEntriesDisabled = false;
                break;
            }
        }
        return allEntriesDisabled;
    }

    public void resetTotalElapsedTimePs() {
        totalElapsedTimePs = 0;
    }


    public long getTotalElapsedTimePs() {
        return totalElapsedTimePs;
    }

    /**
     * This is for tests only
     * @param totalElapsedTimePs
     */
    public void setTotalElapsedTimePsForDebug(long totalElapsedTimePs) {
        this.totalElapsedTimePs = totalElapsedTimePs;
    }

    public String getFormatedTotalElapsedTimeMs() {
        return milliSecondFormatter.format(totalElapsedTimePs/(double)PS_PER_MS) + "ms";
    }


    //
    // Code from http://stackoverflow.com/questions/4201860/how-to-find-gcf-lcm-on-a-set-of-numbers
    // Author Jeffrey Hantin
    //

    /**
     * Greatest common divider (long)
     * @param a
     * @param b
     * @return
     */
    private static long longGCD(long a, long b) {
        while (b > 0) {
            long temp = b;
            b = a % b; // % is remainder
            a = temp;
        }
        return a;
    }

    /**
     * Greatest common divider (int)
     * @param a
     * @param b
     * @return
     */
    private static int intGCD(int a, int b) {
        while (b > 0) {
            int temp = b;
            b = a % b; // % is remainder
            a = temp;
        }
        return a;
    }

    /**
     * Least common multiple (long)
     * A little trickier, but probably the best approach is reduction by the GCD,
     * which can be similarly iterated:
     */
    private static long longLCM(long a, long b) {
        return a * (b / longGCD(a, b));
    }

    /**
     * Least common multiple (int)
     * A little trickier, but probably the best approach is reduction by the GCD,
     * which can be similarly iterated:
     */
    private static int intLCM(int a, int b) {
        return a * (b / intGCD(a, b));
    }

    public void setupClockableCallbackHandlers(ClockableCallbackHandler[] clockableCallbackHandlers) {
        this.clockableCallbackHandlers = clockableCallbackHandlers;
    }

    // This is a wrapper for the device, its counter value and its counter threshold
    static class ClockableEntry {
        final Clockable        clockable;
        final int clockableCallbackHandlerChip;
        int counterValue     = 0;
        int counterThreshold = 0;
        boolean enabled;
        boolean isFrequencyZero;
        boolean isPrecise;

        public ClockableEntry(Clockable clockable, int clockableCallbackHandlerChip, boolean enabled, boolean isPrecise) {
            this.clockable = clockable;
            this.clockableCallbackHandlerChip = clockableCallbackHandlerChip;
            this.enabled = enabled;
            this.isPrecise = isPrecise;
        }

        @Override
        public String toString() {
            return "ClockableEntry (" + (enabled ?"ON":"OFF") +") for " + clockable + '}';
        }
    }

    static class ClockExecutionStep {
        long stepDurationPs;
        List<ClockableEntry> entriesToRunAtThisStep = new ArrayList<>();
    }
}
