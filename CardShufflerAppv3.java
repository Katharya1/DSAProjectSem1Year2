package CardShufflerProject;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Stack;
import javax.swing.*;

/**
 * Pancake-sort (prefix reversal) visualiser.
 *
 * Data structures on show:
 *   ArrayDeckStack - array-backed stack holding the live deck
 *   Stack<Step>    - LIFO undo / redo history
 *   Queue<Step>    - FIFO script of flips that the animation timer plays back
 *
 * Visual language:
 *   raised stack     = the first k cards that the current flip is reversing, lifted off the table
 *   gold card        = the "pancake" (the max card the flip is moving), inside the raised stack
 *   orange border    = the mirror pair being swapped right now, raised ABOVE the stack
 *   blue card        = already in its final sorted slot
 */
public class CardShufflerAppv3 extends JFrame {

    // ====================================================================
    //  Domain model
    // ====================================================================

    /** A playing card. {@code value} is the sort key; suit and rank are derived for display only. */
    public static class Card {
        private static final String[] SUITS = {"♠", "♥", "♦", "♣"};
        private static final String[] RANKS = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};

        private final int value; // 1..52 (this app uses 1..DECK_SIZE)
        private final String suit;
        private final String rank;

        public Card(int value) {
            this.value = value;
            this.suit = SUITS[(value - 1) / 13];
            this.rank = RANKS[(value - 1) % 13];
        }

        public int getValue() { return value; }
        public String getSuit() { return suit; }
        public String getRank() { return rank; }

        @Override
        public String toString() { return rank + suit; }
    }

    /**
     * Fixed-capacity stack backed by an array, used as the live deck.
     * Index 0 is the "front" where the spatula flips; the sorted pile builds up at the back.
     */
    public static class ArrayDeckStack {
        private final Card[] array;
        private int top = -1; // index of the last card; -1 when empty

        public ArrayDeckStack(int capacity) { array = new Card[capacity]; }

        public void push(Card card) {
            if (top < array.length - 1) array[++top] = card;
        }

        // Basic stack interface (the app itself only pushes while dealing the deck)
        public Card pop() { return isEmpty() ? null : array[top--]; }
        public boolean isEmpty() { return top == -1; }

        public int size() { return top + 1; }
        public Card get(int index) { return array[index]; }

        public void swap(int i, int j) {
            Card temp = array[i];
            array[i] = array[j];
            array[j] = temp;
        }

        /**
         * Prefix reversal, the pancake sort's only move: reverses the first k cards in place.
         * Flipping the same k twice restores the deck, so a flip is its own inverse. That is why
         * undo and redo can simply replay the same flip instead of storing deck snapshots.
         */
        public void flip(int k) {
            if (k > size()) return;
            for (int left = 0, right = k - 1; left < right; left++, right--) swap(left, right);
        }

        /** Independent copy (cards are shared, the array is not) so the sort can be planned off-screen. */
        public ArrayDeckStack copy() {
            ArrayDeckStack copy = new ArrayDeckStack(array.length);
            for (int i = 0; i < size(); i++) copy.push(array[i]);
            return copy;
        }

        public Card[] toArray() { return Arrays.copyOf(array, size()); }
    }

    /**
     * One planned prefix flip. The same Step object is queued for animation, pushed on the undo
     * stack, and replayed for undo/redo.
     */
    private static class Step {
        final int k;              // flip the first k cards (indices 0..k-1)
        final Card target;        // the "pancake": the max card this flip is moving
        final boolean placesCard; // false = Step A (bring target to the top), true = Step B (send it to its final slot)

        Step(int k, Card target, boolean placesCard) {
            this.k = k;
            this.target = target;
            this.placesCard = placesCard;
        }

        /** Built lazily (only when displayed) so it doesn't pollute the timed sort calculation. */
        String describe(boolean undone) {
            // Step A moves the target to the top, Step B moves it to idx k-1; undo runs the same flip backwards.
            boolean endsAtTop = (placesCard == undone);
            String verb = undone ? "return" : (placesCard ? "send" : "bring");
            String dest = endsAtTop ? "top" : "idx " + (k - 1);
            return (placesCard ? "Step B: " : "Step A: ") + verb + " " + target + " to " + dest + " (flip " + k + ")";
        }
    }

    /** What the animation engine is replaying. Only affects history bookkeeping and log text. */
    private enum Mode {
        SORT("Sort"), UNDO("↩ Undo"), REDO("↪ Redo");

        final String label;

        Mode(String label) { this.label = label; }
    }

    // ====================================================================
    //  Configuration
    // ====================================================================

    private static final int DECK_SIZE = 52;     // small enough to follow card by card
    private static final int TICK_MS = 600;      // normal animation beat
    private static final int FAST_TICK_MS = 240; // beat once the pancake has moved (the rest of the flip just mirrors the other cards)

    // Card layout (px). Each card is drawn on one of three layers/heights.
    private static final int CARD_W = 75, CARD_H = 110, START_X = 30, START_Y = 100, SPACING_X = 52, LEGEND_Y = 28;
    private static final int RESTING = 0, STACK = 1, SWAPPING = 2;
    private static final int[] LIFT_PX = {0, 22, 48}; // by layer: the swapping pair always floats above the flipping stack

    private static final Color SORTED_COLOR = new Color(173, 205, 240);
    private static final Color TARGET_COLOR = new Color(255, 205, 60);
    private static final Color PAIR_BORDER = new Color(230, 90, 20);
    private static final Color RED_SUIT = new Color(200, 0, 0);
    private static final Color SHADOW_COLOR = new Color(0, 0, 0, 70);
    private static final Font CARD_FONT = new Font("Arial", Font.BOLD, 14);
    private static final Font INDEX_FONT = new Font("Arial", Font.PLAIN, 11);
    private static final BasicStroke THIN_STROKE = new BasicStroke(1f);
    private static final BasicStroke THICK_STROKE = new BasicStroke(3f);
    private static final String CAPTION =
            "Run Pancake Sort queues every flip (FIFO); Undo/Redo replay flips from the LIFO stacks.";

    // ====================================================================
    //  State
    // ====================================================================

    private ArrayDeckStack liveDeck;
    private final Stack<Step> undoStack = new Stack<>();
    private final Stack<Step> redoStack = new Stack<>();
    private final Queue<Step> animationQueue = new LinkedList<>();

    // Benchmark metrics
    private int pancakeFlipCount = 0;    // flips planned for the current pancake run
    private int pancakeCompsCount = 0;   // comparisons made while searching for maximums
    private long pancakeExecTimeNs = 0;
    private int mergeCompsCount = 0;
    private long mergeExecTimeNs = 0;

    // Animation state
    private final Timer animationTimer = new Timer(TICK_MS, e -> tick());
    private Mode mode = Mode.SORT;
    private Step currentStep = null;    // flip being animated; drives the pancake highlight (null when idle)
    private int currentStepNumber = 0;  // 1-based index of the sort step being animated
    private int swapLeft = 0;           // next mirror pair (swapLeft, swapRight) to swap in the current flip
    private int swapRight = 0;
    private int liftedLeft = -1;        // the pair currently raised above the pancake (-1 = none)
    private int liftedRight = -1;
    private boolean swapPending = false; // true = pair is raised, next tick swaps it
    private int sortedFromIndex = DECK_SIZE; // cards at indices >= this are in their final slot

    // UI
    private JPanel deckVisualCanvas;
    private JTextArea metricsLogArea;
    private JTextArea stepLogArea;

    public CardShufflerAppv3() {
        setTitle("DSA Project Showcase: Prefix Reversal Card Shuffler");
        setSize(1100, 650);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        initializeDeck();
        initUIComponents();
    }

    private void initializeDeck() {
        ArrayList<Card> cards = new ArrayList<>();
        for (int i = 1; i <= DECK_SIZE; i++) cards.add(new Card(i));
        Collections.shuffle(cards);

        liveDeck = new ArrayDeckStack(DECK_SIZE);
        for (Card c : cards) liveDeck.push(c);

        undoStack.clear();
        redoStack.clear();
        animationQueue.clear();
        currentStepNumber = 0;
        resetAnimationState();
        refreshSortedRegion();
    }

    /** Clears every transient animation flag (no pair raised, no active flip). */
    private void resetAnimationState() {
        liftedLeft = liftedRight = -1;
        swapLeft = swapRight = 0;
        swapPending = false;
        currentStep = null;
    }

    private void scrambleDeck() {
        animationTimer.stop();
        initializeDeck(); // also clears history, queue and animation state
        stepLogArea.setText("");
        updateExecutionMetricsDisplay("Deck scrambled. Ready to benchmark.");
        deckVisualCanvas.repaint();
    }

    // ====================================================================
    //  UI construction
    // ====================================================================

    private void initUIComponents() {
        setLayout(new BorderLayout(10, 10));
        add(buildControlRibbon(), BorderLayout.NORTH);

        deckVisualCanvas = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                renderDeckCanvas((Graphics2D) g);
            }
        };
        deckVisualCanvas.setBackground(new Color(34, 112, 63)); // classic casino green
        add(deckVisualCanvas, BorderLayout.CENTER);

        add(buildDiagnosticPanel(), BorderLayout.EAST);
    }

    private JPanel buildControlRibbon() {
        JPanel ribbon = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 10));
        ribbon.setBackground(new Color(240, 242, 245));

        JButton btnShuffle = new JButton("# Scramble Deck");
        JButton btnUndo = new JButton("↩️ Undo Move");
        JButton btnRedo = new JButton("↪️ Redo Move");
        JButton btnSolvePancake = new JButton("⚡ Run Pancake Sort");
        JButton btnBenchmarkMerge = new JButton("# Profile Merge Sort");

        btnShuffle.addActionListener(e -> scrambleDeck());
        btnUndo.addActionListener(e -> handleUndo());
        btnRedo.addActionListener(e -> handleRedo());
        btnSolvePancake.addActionListener(e -> executePancakeSortPipeline());
        btnBenchmarkMerge.addActionListener(e -> executeMergeSortProfiling());

        ribbon.add(btnShuffle);
        ribbon.add(new JSeparator(JSeparator.VERTICAL));
        ribbon.add(btnUndo);
        ribbon.add(btnRedo);
        ribbon.add(new JSeparator(JSeparator.VERTICAL));
        ribbon.add(btnSolvePancake);
        ribbon.add(btnBenchmarkMerge);
        return ribbon;
    }

    private JPanel buildDiagnosticPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(340, 0));
        panel.setBorder(BorderFactory.createTitledBorder("Execution Profiles & Metrics"));

        metricsLogArea = createLogArea();
        panel.add(new JScrollPane(metricsLogArea), BorderLayout.CENTER);

        // Step-by-step log; auto-scrolls to the newest line (see appendStepLog)
        stepLogArea = createLogArea();
        JScrollPane stepLogScroll = new JScrollPane(stepLogArea);
        stepLogScroll.setPreferredSize(new Dimension(0, 190));
        stepLogScroll.setBorder(BorderFactory.createTitledBorder("Pancake Step Log"));
        panel.add(stepLogScroll, BorderLayout.SOUTH);
        return panel;
    }

    private static JTextArea createLogArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font("Monospaced", Font.PLAIN, 12));
        return area;
    }

    // ====================================================================
    //  Canvas rendering
    // ====================================================================

    private void renderDeckCanvas(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setFont(CARD_FONT);

        // Colour legend so the highlights explain themselves
        int legendX = START_X;
        legendX = drawLegendItem(g2, legendX, SORTED_COLOR, Color.BLACK, "Sorted");
        legendX = drawLegendItem(g2, legendX, TARGET_COLOR, Color.BLACK, "Pancake");
        legendX = drawLegendItem(g2, legendX, Color.WHITE, Color.BLACK, "Flipping stack (raised)");
        drawLegendItem(g2, legendX, Color.WHITE, PAIR_BORDER, "Swapping pair (higher)");

        int count = liveDeck.size();
        int[] layer = new int[count];
        for (int i = 0; i < count; i++) layer[i] = layerOf(i);

        // 1) Shadows on the table first, so every card (raised or not) is painted on top of them
        g2.setColor(SHADOW_COLOR);
        for (int i = 0; i < count; i++) {
            if (layer[i] != RESTING) g2.fillRoundRect(START_X + i * SPACING_X + 6, START_Y + 6, CARD_W, CARD_H, 12, 12);
        }

        // 2) Cards, lowest layer first. Cards overlap horizontally, so painting in z-order guarantees a
        //    raised card is never hidden behind a resting neighbour.
        for (int l = RESTING; l <= SWAPPING; l++) {
            for (int i = 0; i < count; i++) {
                if (layer[i] == l) drawCard(g2, i, l);
            }
        }

        g2.setColor(new Color(255, 255, 255, 200));
        g2.drawString(CAPTION, 30, deckVisualCanvas.getHeight() - 40);
    }

    /**
     * Which height a card is drawn at:
     * SWAPPING = in the pair being swapped right now (highest), STACK = anywhere in the prefix (indices
     * 0..k-1) that the current flip is reversing, RESTING = everything else.
     */
    private int layerOf(int i) {
        if (i == liftedLeft || i == liftedRight) return SWAPPING;
        return (currentStep != null && i < currentStep.k) ? STACK : RESTING;
    }

    private boolean isTarget(int i) {
        return currentStep != null && liveDeck.get(i) == currentStep.target;
    }

    private void drawCard(Graphics2D g2, int i, int layer) {
        Card card = liveDeck.get(i);
        int x = START_X + i * SPACING_X;
        int y = START_Y - LIFT_PX[layer];

        // Fill priority: sorted (blue) > pancake (gold) > plain white
        g2.setColor(i >= sortedFromIndex ? SORTED_COLOR : isTarget(i) ? TARGET_COLOR : Color.WHITE);
        g2.fillRoundRect(x, y, CARD_W, CARD_H, 12, 12);

        // The swapping pair gets a thick orange border so it reads differently from the gold pancake
        boolean swapping = (layer == SWAPPING);
        g2.setStroke(swapping ? THICK_STROKE : THIN_STROKE);
        g2.setColor(swapping ? PAIR_BORDER : Color.BLACK);
        g2.drawRoundRect(x, y, CARD_W, CARD_H, 12, 12);
        g2.setStroke(THIN_STROKE);

        boolean red = card.getSuit().equals("♥") || card.getSuit().equals("♦");
        g2.setColor(red ? RED_SUIT : Color.BLACK);
        g2.drawString(card.getRank(), x + 8, y + 22);
        g2.drawString(card.getSuit(), x + 8, y + 42);

        // Index label uses a fixed baseline (not the lifted y) so each slot stays readable
        g2.setColor(Color.YELLOW);
        g2.setFont(INDEX_FONT);
        g2.drawString("Idx: " + i, x + 20, START_Y + CARD_H + 20);
        g2.setFont(CARD_FONT);
    }

    /** Draws one swatch + label and returns the x where the next legend item should start. */
    private int drawLegendItem(Graphics2D g2, int x, Color fill, Color outline, String label) {
        g2.setColor(fill);
        g2.fillRoundRect(x, LEGEND_Y - 12, 16, 16, 4, 4);
        g2.setStroke(new BasicStroke(2f));
        g2.setColor(outline);
        g2.drawRoundRect(x, LEGEND_Y - 12, 16, 16, 4, 4);
        g2.setStroke(THIN_STROKE);
        g2.setColor(Color.WHITE);
        g2.drawString(label, x + 24, LEGEND_Y);
        return x + 24 + g2.getFontMetrics().stringWidth(label) + 28;
    }

    /**
     * Finds the longest suffix of the deck already in its final slot.
     * The deck holds values 1..N, so the card with value v belongs at index v-1.
     */
    private void refreshSortedRegion() {
        int boundary = liveDeck.size();
        while (boundary > 0 && liveDeck.get(boundary - 1).getValue() == boundary) boundary--;
        sortedFromIndex = boundary;
    }

    private void appendStepLog(String line) {
        stepLogArea.append(line + "\n");
        stepLogArea.setCaretPosition(stepLogArea.getDocument().getLength());
    }

    // ====================================================================
    //  1. Pancake sort: planning
    // ====================================================================

    /**
     * PANCAKE SORT: O(n^2) comparisons, at most 2n-3 flips.
     *
     * The only legal move is a prefix reversal, flip(k), so the deck is built like a selection sort
     * from the back. For size = n down to 2:
     *   1. find the largest card (the "pancake") among the first `size` cards
     *   2. Step A: if it isn't already at index 0, flip(maxIdx + 1) to bring it to the top
     *   3. Step B: flip(size) to send it from the top to index size-1, its final slot
     * After each round everything at index >= size-1 is final, so the unsorted prefix shrinks by one.
     * If the pancake is already at size-1 the round needs no flips at all.
     *
     * The sort runs on a COPY of the deck and records each flip as a Step in the FIFO queue; the
     * animation timer then replays those steps on the live deck.
     */
    private void executePancakeSortPipeline() {
        if (animationTimer.isRunning()) return;
        stepLogArea.setText("");
        pancakeCompsCount = 0;

        ArrayDeckStack plan = liveDeck.copy();
        long startTime = System.nanoTime();
        for (int size = plan.size(); size > 1; size--) {
            int maxIdx = findMaxElementIndex(plan, size);
            if (maxIdx == size - 1) continue; // already in place

            Card pancake = plan.get(maxIdx);
            if (maxIdx != 0) { // Step A
                plan.flip(maxIdx + 1);
                animationQueue.add(new Step(maxIdx + 1, pancake, false));
            }
            plan.flip(size);   // Step B
            animationQueue.add(new Step(size, pancake, true));
        }
        pancakeExecTimeNs = System.nanoTime() - startTime;
        pancakeFlipCount = animationQueue.size();

        if (animationQueue.isEmpty()) {
            updateExecutionMetricsDisplay("Deck is already sorted: 0 flips needed.");
            return;
        }
        startAnimation(Mode.SORT, String.format("Pancake Sort planned %d flips using %d comparisons. Animating...",
                pancakeFlipCount, pancakeCompsCount));
    }

    /** Index of the largest card among the first `size` cards (size-1 comparisons). */
    private int findMaxElementIndex(ArrayDeckStack deck, int size) {
        int maxIdx = 0;
        for (int i = 1; i < size; i++) {
            pancakeCompsCount++;
            if (deck.get(i).getValue() > deck.get(maxIdx).getValue()) maxIdx = i;
        }
        return maxIdx;
    }

    // ====================================================================
    //  2. Merge sort: baseline benchmark (sequential, runs on a copy)
    // ====================================================================

    private void executeMergeSortProfiling() {
        Card[] benchmarkDeck = liveDeck.toArray();
        mergeCompsCount = 0;
        long startTime = System.nanoTime();
        mergeSortSlice(benchmarkDeck, 0, benchmarkDeck.length - 1);
        mergeExecTimeNs = System.nanoTime() - startTime;
        updateExecutionMetricsDisplay("Baseline Merge Sort profiled on a copy of the current deck.");
    }

    private void mergeSortSlice(Card[] arr, int l, int r) {
        if (l < r) {
            int m = l + (r - l) / 2;
            mergeSortSlice(arr, l, m);
            mergeSortSlice(arr, m + 1, r);
            mergeCombiner(arr, l, m, r);
        }
    }

    private void mergeCombiner(Card[] arr, int l, int m, int r) {
        Card[] leftSlice = Arrays.copyOfRange(arr, l, m + 1);
        Card[] rightSlice = Arrays.copyOfRange(arr, m + 1, r + 1);
        int i = 0, j = 0, k = l;
        while (i < leftSlice.length && j < rightSlice.length) {
            mergeCompsCount++;
            arr[k++] = (leftSlice[i].getValue() <= rightSlice[j].getValue()) ? leftSlice[i++] : rightSlice[j++];
        }
        while (i < leftSlice.length) arr[k++] = leftSlice[i++];
        while (j < rightSlice.length) arr[k++] = rightSlice[j++];
    }

    // ====================================================================
    //  3. Animation engine (sort, undo and redo all share it)
    // ====================================================================

    /** Starts playing whatever is in the animation queue. */
    private void startAnimation(Mode newMode, String message) {
        mode = newMode;
        resetAnimationState();
        updateExecutionMetricsDisplay(message);
        setNextTickIn(TICK_MS);
    }

    /**
     * Reschedules the timer so the NEXT tick fires in `ms`. (Timer.setDelay alone only takes effect
     * one tick late, because Swing has already scheduled the upcoming tick.) Also starts a stopped timer.
     */
    private void setNextTickIn(int ms) {
        animationTimer.setInitialDelay(ms);
        animationTimer.setDelay(ms);
        animationTimer.restart();
    }

    /**
     * Animation heartbeat, one call per timer tick. A flip of k cards plays out as:
     *   PICK - dequeue the next Step; its k-card stack rises and the pancake turns gold
     *   LIFT - the next mirror pair (i, k-1-i) rises above the stack
     *   SWAP - the raised pair trades places
     * LIFT/SWAP repeat until the pairs meet in the middle (an odd k leaves the centre card in place).
     * The pair swapped on the previous tick is lowered at the start of the next one.
     */
    private void tick() {
        String status;
        if (swapPending) {
            status = swapLiftedPair();                  // SWAP
        } else {
            liftedLeft = liftedRight = -1;              // lower the previously swapped pair
            if (swapLeft < swapRight) {
                status = liftNextPair();                // LIFT
            } else {                                    // no pairs left: the flip is complete
                refreshSortedRegion();                  // full check only BETWEEN flips, so shading can't flicker mid-flip
                if (animationQueue.isEmpty()) {
                    finishRun();
                    return;
                }
                status = beginStep(animationQueue.poll()); // PICK
            }
        }
        updateExecutionMetricsDisplay(status);
        deckVisualCanvas.repaint();
    }

    /** PICK: make `step` the active flip. Its k-card stack is raised and the pancake highlighted; no pair is lifted yet. */
    private String beginStep(Step step) {
        currentStep = step;
        swapLeft = 0;
        swapRight = step.k - 1;
        setNextTickIn(TICK_MS);

        String prefix = mode.label;
        if (mode == Mode.SORT) {
            currentStepNumber++;
            undoStack.push(step); // one history entry per whole flip, recorded before its swaps
            redoStack.clear();
            prefix = String.format("%02d/%02d", currentStepNumber, pancakeFlipCount);
        }
        appendStepLog(prefix + " " + describe(step));
        return describe(step) + "\nLifted the top " + step.k + " cards; pancake " + step.target + " highlighted.";
    }

    /** LIFT: raise the next mirror pair above the pancake. */
    private String liftNextPair() {
        liftedLeft = swapLeft;
        liftedRight = swapRight;
        swapPending = true;
        return describe(currentStep) + "\nRaised idx " + liftedLeft + " and idx " + liftedRight + " above the stack.";
    }

    /** SWAP: exchange the raised pair on the live deck, then advance to the next pair. */
    private String swapLiftedPair() {
        liveDeck.swap(swapLeft, swapRight);
        String status = describe(currentStep) + "\nSwapped idx " + swapLeft + " and idx " + swapRight + ".";
        if (swapLeft == 0) onPancakeMoved();
        swapPending = false;
        swapLeft++;
        swapRight--;
        return status;
    }

    /**
     * The first mirror pair of every flip is (0, k-1) and always contains the pancake: Step A carries it
     * from k-1 up to 0, Step B from 0 down to k-1 (undo/redo replay the same flips). So after the first swap
     * the pancake has reached its destination, and we:
     *   - update the shading: the pancake is shaded the instant it lands in its final slot, while a
     *     pancake leaving that slot (undoing Step B) un-shades the whole flipped prefix;
     *   - speed up, because the remaining pairs only mirror the other cards.
     */
    private void onPancakeMoved() {
        int home = currentStep.target.getValue() - 1; // final slot of the pancake in a sorted deck
        boolean landed = liveDeck.get(home) == currentStep.target;
        sortedFromIndex = landed ? Math.min(sortedFromIndex, home) : Math.max(sortedFromIndex, currentStep.k);
        setNextTickIn(FAST_TICK_MS);
    }

    private void finishRun() {
        animationTimer.stop();
        currentStep = null;
        if (mode == Mode.SORT) appendStepLog("Done: deck sorted.");
        updateExecutionMetricsDisplay(mode.label + " complete. Animation queue flushed.");
        deckVisualCanvas.repaint();
    }

    /** Describes a step from the current mode's point of view (undo describes the flip backwards). */
    private String describe(Step step) {
        return step.describe(mode == Mode.UNDO);
    }

    // ====================================================================
    //  4. Undo / redo (LIFO stacks of Steps, animated like the sort)
    // ====================================================================

    // Undo/redo replay the same flip, so they get the same pancake/pair highlighting as the sort.

    private void handleUndo() {
        if (animationTimer.isRunning()) return;
        if (undoStack.isEmpty()) {
            showEmptyHistory("Undo");
            return;
        }
        Step step = undoStack.pop();
        redoStack.push(step);
        replay(step, Mode.UNDO);
    }

    private void handleRedo() {
        if (animationTimer.isRunning()) return;
        if (redoStack.isEmpty()) {
            showEmptyHistory("Redo");
            return;
        }
        Step step = redoStack.pop();
        undoStack.push(step);
        replay(step, Mode.REDO);
    }

    /** Animates a single flip on the live deck. A flip is its own inverse, so this serves undo and redo alike. */
    private void replay(Step step, Mode replayMode) {
        animationQueue.add(step);
        startAnimation(replayMode, replayMode.label + ": " + step.describe(replayMode == Mode.UNDO));
    }

    private void showEmptyHistory(String stackName) {
        JOptionPane.showMessageDialog(this, "No moves left in the " + stackName + " history.", "History End",
                JOptionPane.INFORMATION_MESSAGE);
    }

    // ====================================================================
    //  Metrics panel
    // ====================================================================

    private void updateExecutionMetricsDisplay(String systemMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SYSTEM LOG STATUS ===\n");
        sb.append(systemMessage).append("\n\n");
        sb.append("=== LIVE TRACKING MATRICES ===\n");
        sb.append(String.format("• Current Live Deck Size : %d cards\n", liveDeck.size()));
        sb.append(String.format("• LIFO Undo Stack Depth : %d elements\n", undoStack.size()));
        sb.append(String.format("• LIFO Redo Stack Depth : %d elements\n", redoStack.size()));
        sb.append(String.format("• FIFO Animation Backlog: %d operations\n", animationQueue.size()));
        sb.append(String.format("• Sorted Region Size    : %d / %d cards\n", liveDeck.size() - sortedFromIndex, liveDeck.size()));
        sb.append(String.format("• Pancake Step Progress : %d / %d steps\n\n", currentStepNumber, pancakeFlipCount));
        sb.append("=== ALGORITHMIC COMPLEXITY BENCHMARKS ===\n");
        sb.append("[1] PANCAKE SORT (Prefix Reversals)\n");
        sb.append(" -> Complexity Profiles: Worst-Case O(n²)\n");
        sb.append(String.format(" -> Total Comparisons   : %d\n", pancakeCompsCount));
        sb.append(String.format(" -> Pure Calculation Time: %.6f s\n\n", pancakeExecTimeNs / 1_000_000_000.0));
        sb.append("[2] BASELINE MERGE SORT\n");
        sb.append(" -> Complexity Profiles: Stable O(n log n)\n");
        sb.append(String.format(" -> Metric Target Checks : %d comparisons\n", mergeCompsCount));
        sb.append(String.format(" -> Pure Calculation Time: %.6f s\n\n", mergeExecTimeNs / 1_000_000_000.0));
        sb.append("=========================================\n");
        metricsLogArea.setText(sb.toString());
        metricsLogArea.setCaretPosition(0);
    }

    // --- Entry point ---
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new CardShufflerAppv3().setVisible(true));
    }
}
