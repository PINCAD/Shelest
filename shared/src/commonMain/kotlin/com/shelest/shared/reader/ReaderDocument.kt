package com.shelest.shared.reader
import kotlinx.serialization.json.*

/** Platform-neutral appearance. Values come from trusted app settings, never book markup. */
data class ReaderDocumentStyle(
    val fontCss: String, val fontLoad: String, val fontSize: Int,
    val lineHeight: String, val margin: String, val background: String, val foreground: String,
    val accent: String, val selection: String, val align: String, val animation: String,
    val json: String, val fontFaceCss: String = ""
)

/** One document engine for Android WebView and browser iframe; bridges/storage stay outside. */
fun buildReaderHtml(
    content: String, style: ReaderDocumentStyle, savedProgress: Double = 0.0,
    hasPrevious: Boolean = false, hasNext: Boolean = false, position: String = "null",
    segmentIndex: Int = 0, isHorizontal: Boolean = true, bridgeJs: String = "",
    backgroundMeasurement: Boolean = false
): String {
    val bg = style.background
    val fg = style.foreground
    val accent = style.accent
    val selColor = style.selection
    val justify = style.align
    val fs = style.fontSize
    val lh = style.lineHeight
    val mx = style.margin
    val fontLoad = style.fontLoad
    val fontFamilyCss = style.fontCss
    val fontFaceCss = style.fontFaceCss
    val styleJson = scriptJson(style.json)
    val positionJson = scriptJson(position)
    val waitForImages = needsImagesBeforePaging(content)
    val spread = isHorizontal && runCatching {Json.parseToJsonElement(style.json).jsonObject["spread"]?.jsonPrimitive?.booleanOrNull == true}.getOrDefault(false)

    val layoutCss = if (isHorizontal) """
        * { box-sizing: border-box; }
        html, body { margin:0; padding:0; width:100%; height:100%; overflow:hidden; background:var(--bg); }
        /* Nothing on a paged screen scrolls, and saying so is not a tidiness note — it is
           the single biggest source of start-up lag. Left unsaid, the WebView holds the
           first touchmove events back while it decides whether the finger is starting a
           scroll or a fling, and only then hands them over. That wait is why the paper
           used to sit still for a moment after the finger had already moved; a native
           reader never pays it. Declared here, events arrive from the very first pixel. */
        html, body { touch-action:none; overscroll-behavior:none; }
        /* The frame is what the "flip" animation rotates — it is exactly the viewport, so
           tilting it reads as the whole visible page turning. Its own background matters
           for that: without one the tilt would reveal a transparent wedge. */
        /* Hidden rather than auto, and still the thing that scrolls: a box with hidden overflow
           has no scrollbar and no finger of its own, but it can be scrolled by script — which is
           exactly the arrangement the paging wants, since every movement of the page here comes
           from a gesture this document interprets itself. */
        #page-frame {
          position:fixed; top:0; left:0; width:100vw; height:100vh; overflow:hidden;
          background:var(--bg); backface-visibility:hidden;
        }
        #mask {
          position:absolute; top:0; left:0; width:100%; height:100%; overflow:hidden;
          background:var(--bg); overflow-anchor:none;
        }
        /* Give the final page its full scroll range without widening the column flow.
           Widening that flow lets Blink redistribute column widths after a resize. */
        #mask::after {
          content:''; position:absolute; top:0; left:0; height:1px;
          width:var(--book-width, 100%); pointer-events:none;
        }
        /* One flow, and only one. The fold used to need two more copies of it, because a
           folding page has to be visible on its front and its back at once and a node can
           only be in one place — three chapter-sized multicolumn layouts in the document,
           relaid on every restyle. The fold is drawn from a picture now (PageCurl.kt), so
           the document is back to holding the book once. */
        /* The side margins are the gutter between columns, not a padding on the text.
           A multicolumn flow puts its own padding at the very start and the very end of the
           whole flow, not on each column — so the margin used to be painted onto a list of tags
           instead: p, h1..h6, blockquote, ul, ol. Any book that wrapped its paragraphs in
           anything else got no margin at all, and moving the slider in the settings changed
           nothing, because nothing on the page was reading the value. Columns one margin
           narrower than the screen, separated by two, put exactly the same gap down both sides
           of every page whatever the markup happens to be. The arithmetic is exact: column i
           begins at mx + i * 100vw, which is what the paging offset already assumes. */
        #container {
          position:absolute; top:0; left:0; width:100vw; height:100vh;
          /* One viewport-sized column box, with subsequent pages overflowing to the
             right. Its width never becomes the width of the entire book. */
          column-count:${if (spread) 2 else 1}; column-width:auto;
          column-gap:calc(var(--mx) * 2);
          column-fill:auto;
          padding:12px var(--mx) 20px;
          opacity:0;
          transition:opacity 0.15s ease-in;
          /* Not transform. Asking to promote a layer as wide as the whole book is asking for
             something the browser cannot give, and what it does instead — repaint the page out
             of the book's display list on every turn — is the eight-second page turn. The flow
             does not move at all now; see setOffset. */
          will-change:opacity;
        }

        p, div, span, blockquote, section, article, .poem, .epigraph, .cite, ul, ol, li {
          widows:1 !important;
          orphans:1 !important;
          -webkit-column-break-inside:auto !important;
          break-inside:auto !important;
          page-break-inside:auto !important;
        }
        h1, h2, h3, h4, h5, h6 {
          -webkit-column-break-after:avoid;
          break-after:avoid;
          page-break-after:avoid;
        }
        /* An illustration is bounded by the page it has to fit on, not by its own pixel size:
           a full-page cover at its natural height would be several screens tall, and a column
           it cannot fit into is a column the text is pushed out of. */
        img {
          max-width:100%; max-height:calc(var(--page-height, 100vh) * 0.86); width:auto; height:auto;
          margin:1em auto; display:block; border-radius:6px;
          -webkit-column-break-inside:avoid; break-inside:avoid; page-break-inside:avoid;
        }
        /* A comic is nothing but pictures, and a page of one is a page — so it takes the screen
           to itself and the next one starts on the next turn, rather than two half-pages
           sharing a column with a paragraph's worth of space between them. */
        img.cbz-page {
          max-height:calc(var(--page-height, 100vh) * 0.96); margin:0 auto; border-radius:0;
          -webkit-column-break-after:always; break-after:column; page-break-after:always;
        }
    """.trimIndent() else """
        * { box-sizing: border-box; }
        html, body { margin:0; padding:0; width:100%; background:var(--bg); }
        #mask { width:100%; }
        #container {
          position:relative; width:100%; max-width:720px; margin:0 auto;
          padding:20px var(--mx) 48px;
        }
        img {
          max-width:100%; height:auto; display:block; margin:1em auto; border-radius:6px;
        }
        /* Scrolling through a comic is a strip, the way they are read on a phone anywhere else:
           full width, and nothing between one page and the next. */
        img.cbz-page { width:100%; margin:0 auto 2px; border-radius:0; }
    """.trimIndent()

    val scriptJs = if (isHorizontal) """
        // The script sits at the end of the body, so reaching this line means the browser has
        // finished reading the book. Everything after it — the pictures, the reading face, the
        // columns being counted — is timed separately, because on a long book the wait before
        // the first page is several of these one after another and they need telling apart.
        Shelest.onTiming('document read', Math.round(performance.now()));
        var container = document.getElementById('container');
        var mask = document.getElementById('mask');
        var pageFrame = document.getElementById('page-frame');
        var pageWidth = window.innerWidth, pageHeight = window.innerHeight;
        var currentPage = 0, totalPages = 1;
        var savedProgress = $savedProgress;
        var canOpenPrevious = $hasPrevious;
        var canOpenNext = $hasNext;
        var animMode = '${style.animation}';
        var animTimer = null;

        // ── Fold-based page turn ("Как в книге") ──────────────────────────────
        //
        // The fold is not drawn here. It used to be — three copies of the chapter and a
        // rotated clip moved sixty times a second — and it could not be made smooth,
        // because a fold line at an angle is not a clip a browser compositor can apply
        // as a rectangle. Every frame went back to the main thread and repainted the
        // text underneath.
        //
        // So this side now only reports the gesture. The app takes one picture of the
        // page and folds the picture natively; see PageCurl.kt. What is left here is
        // small on purpose: the WebView is the only thing that knows whether the finger
        // is on a word being selected, and that judgement stays where it belongs.
        var curlActive = false;    // a native fold is on screen
        var curlDrag = false;      // …and a finger is holding it

        // Called from the app once its overlay is covering the screen: moves the page
        // underneath to the destination, unseen, so the fold has something to uncover.
        window.curlReveal = function(n) {
          container.style.transition = 'none';
          container.style.opacity = '1';
          pageFrame.style.transition = 'none';
          pageFrame.style.transform = 'none';
          setOffset(n);
          // Force the jump to be a jump. Without a reflow the browser is free to fold
          // this into the next recalc alongside whatever the following turn sets, and a
          // page that was supposed to move instantly slides instead — behind a fold, in
          // the one place a slide must not happen.
          container.offsetHeight;
        };

        // …and once it has finished, wherever it finished. This is the only place a
        // curl turn commits, so a turn that was pulled back leaves no trace.
        window.curlLand = function(n) {
          curlActive = false;
          curlDrag = false;
          container.style.transition = 'none';
          setOffset(n);
          container.offsetHeight;
          if (currentPage !== n) {
            currentPage = n;
            reportExactPosition(-1);
            Shelest.onPageChange(currentPage, totalPages, -1, positionSegment);
            schedulePlace();
          }
        };

        // The pictures the fold needs weren't ready — the page was jumped to from the
        // contents or a search hit and nothing has settled long enough to be
        // photographed. Turn the page the plain way; a page that slides is a great deal
        // better than one that jumps, and the reader gets the fold back next time.
        window.curlFallback = function(n) {
          curlActive = false;
          curlDrag = false;
          var was = animMode;
          animMode = 'slide';
          goToPage(n, true);
          animMode = was;
        };

        // Anything that moves the page out from under a running fold — a search hit, a
        // jump from the contents, a change of animation mode.
        function abortCurl() {
          if (!curlActive) return;
          curlActive = false;
          curlDrag = false;
          Shelest.onCurlAbort();
        }

        var lastSelection = "";
        document.addEventListener('selectionchange', function() {
          var s = window.getSelection().toString().trim();
          if (s) { lastSelection = s; }
        });

        function getSelectedText() {
          var s = window.getSelection().toString().trim();
          if (!s && document.getSelection) s = document.getSelection().toString().trim();
          if (!s && lastSelection) s = lastSelection;
          return s ? s : "";
        }

        function viewportSize() {
          // Computed dimensions ignore the flip animation's transform and retain the
          // fractional CSS pixels used by WebViews with non-integer screen density.
          var s = getComputedStyle(pageFrame);
          // Quantise width to whole CSS pixels: Blink's internal zoom on Android
          // can otherwise give the column stride a different fractional unit
          // from scrollLeft. Height retains layout precision without horizontal drift.
          return {width: Math.floor(parseFloat(s.width) || window.innerWidth),
                  height: Math.round((parseFloat(s.height) || window.innerHeight) * 64) / 64};
        }

        // Pin ONE page's dimensions. This also keeps the old text geometry intact
        // until a resize handler has captured its character anchor.
        function measurePages() {
          var size = viewportSize();
          pageWidth = Math.max(1, size.width);
          pageHeight = Math.max(1, size.height);
          // Android WebView may apply an internal zoom before quantising columns.
          // Integer CSS pixels keep both the scrollport and column stride identical
          // at fractional densities, rather than accumulating rounding per page.
          mask.style.width = pageWidth + 'px';
          container.style.width = pageWidth + 'px';
          container.style.height = pageHeight + 'px';
          container.style.setProperty('--page-height', pageHeight + 'px');
          var sw = container.scrollWidth;
          // scrollWidth is rounded to integer CSS pixels; tolerate that rounding,
          // but never drop a genuinely partial final page.
          totalPages = Math.max(1, Math.ceil((sw - 1) / pageWidth));
          mask.style.setProperty('--book-width', (totalPages * pageWidth) + 'px');
        }

        // Set the moment the book is first laid out. Two things can ask for that — whichever of
        // the fonts and the timeout gets there first — and the second must do nothing, or the
        // reader is thrown back to their saved page after they have already turned it.
        var paged = false;
        var readyCalls = [];
        window.runWhenReaderReady = function(call) {
          if (paged) call(); else readyCalls.push(call);
        };

        // The page a character falls on: where it sits on screen, plus the pages already slid
        // past. The +2 absorbs the sub-pixel rounding that would otherwise put a character
        // sitting exactly on the fold onto the page before it.
        function pageOfChar(c) {
          var rect = rectAtChar(c);
          if (!rect) return currentPage;
          // Against the scroll rather than against the page number: a rectangle is measured from
          // the window, and what stands between the window and the book is exactly how far the
          // mask has been scrolled — including half way through a slide, when the page number
          // has already been committed to and the screen has not caught up with it yet.
          return Math.max(0, Math.floor((rect.left + mask.scrollLeft + 2) / pageWidth));
        }

        /** The first character of the page showing. */
        function currentChar() { return charAtPlace(currentPage, pageOfChar); }

        function goToChar(c, animate) {
          goToPage(Math.min(totalPages - 1, pageOfChar(c)), animate);
        }

        /**
         * How far into the book page [n] is, as a percentage of its characters.
         *
         * Asked *before* a turn begins rather than after, because [pageOfChar] reads where things
         * are on screen and a turn is exactly the moment they are somewhere in between: an
         * animated page is mid-transform for the next fifth of a second, and a measurement taken
         * then would describe a page nobody is looking at.
         */
        function progressAtPage(n) {
          if (n <= 0) return 0;
          var t = totalChars();
          return t > 0 ? Math.min(100, Math.max(0, charAtPlace(n, pageOfChar) * 100 / t)) : 0;
        }

        function progressPercent() { return progressAtPage(currentPage); }

        /**
         * The place in the book, in characters, told to the app a moment after the turning stops.
         *
         * Measuring it is not cheap. It is a search through every block of the book, and each of
         * its twenty-odd probes asks the browser where something sits on the screen. Doing that
         * inside every page turn is what made a long book turn slowly: the page itself moves in
         * one transform, and then the reader waits on arithmetic nobody asked to see.
         *
         * So a turn now reports its page number, which costs nothing, and says nothing about the
         * place — the app reads that as "unknown" and falls back on the page number until the
         * exact answer arrives, which it does once the finger has been still for a moment. A
         * exact page is saved separately and immediately; the delayed character anchor is for
         * changes of font or viewport, and is also captured explicitly before leaving.
         */
        var placeTimer = null;

        /**
         * What a turn cost, measured the way a reader feels it: the longest gap between two
         * frames while the page was moving.
         *
         * A smooth turn is frames eight or sixteen milliseconds apart. A gap of a hundred and
         * fifty is the stutter, and it says where the stutter is: the browser missed those
         * frames because something was holding its thread. The count of frames beside it
         * separates that from the other possibility — a turn that is smooth but simply starts
         * late, which shows up as few frames rather than as a long gap.
         */
        var turnFrames = 0, turnLast = 0, turnWorst = 0, turnUntil = 0;

        function watchTurn() {
          var now = performance.now();
          turnUntil = now + 500;
          if (turnFrames > 0) return;
          turnWorst = 0; turnLast = now; turnFrames = 1;
          requestAnimationFrame(function step(t) {
            var gap = t - turnLast;
            turnLast = t;
            if (gap > turnWorst) turnWorst = gap;
            turnFrames++;
            if (t < turnUntil) { requestAnimationFrame(step); return; }
            Shelest.onTiming('turn worst frame', Math.round(turnWorst));
            Shelest.onTiming('turn frames', turnFrames);
            turnFrames = 0;
          });
        }

        function reportPlace() {
          if (placeTimer) { clearTimeout(placeTimer); placeTimer = null; }
          var t0 = Date.now();
          var c = currentChar();
          var t = totalChars();
          var pct = t > 0 ? Math.min(100, Math.max(0, c * 100 / t)) : 0;
          reportExactPosition(c);
          Shelest.onTiming('place', Date.now() - t0);
          Shelest.onPageChange(currentPage, totalPages, pct, positionSegment);
          return c;
        }

        function schedulePlace() {
          if (placeTimer) clearTimeout(placeTimer);
          placeTimer = setTimeout(reportPlace, 600);
        }

        /** Whatever is owed, paid now: the reader is leaving. */
        window.flushPlace = function() { if (placeTimer) reportPlace(); };

        // Going to the background is leaving, as far as the place is concerned.
        document.addEventListener('visibilitychange', function() {
          if (document.hidden) flushPlace();
        });
        window.addEventListener('pagehide', function() { flushPlace(); });

        function initPaging() {
          if (paged) return;
          paged = true;
          // Counted from the moment the browser began loading the document, so it holds the DOM
          // being built and the reading face being fetched — everything before the first
          // measurement, which is the part no timer inside this function can see.
          Shelest.onTiming('before first layout', Math.round(performance.now()));
          var t0 = Date.now();
          measurePages();
          Shelest.onTiming('measure', Date.now() - t0);
          // Same layout: restore the exact page, including pages without text. Changed layout:
          // restore the integer character offset. Percentages are only for pre-migration books.
          // A book opened at the start needs none of this: page one begins at character one, and
          // walking the whole document to be told so is a wait paid for nothing.
          var t1 = Date.now();
          var exactPage = initialReadingPosition && initialReadingPosition.layout === positionLayout();
          var anchor = initialReadingPosition ? initialReadingPosition.character : -1;
          var t = !exactPage && (anchor >= 0 || savedProgress > 0) ? totalChars() : 0;
          Shelest.onTiming('index', Date.now() - t1);
          var t2 = Date.now();
          if (exactPage) goToPage(Math.min(totalPages - 1, initialReadingPosition.page), false);
          else if (anchor >= 0 && t > 0) goToChar(Math.min(anchor, t - 1), false);
          else if (t > 0) goToChar(Math.max(0, Math.min(t - 1, Math.round(savedProgress / 100.0 * t))), false);
          else if (savedProgress >= 100) goToPage(totalPages - 1, false);
          else goToPage(0, false);
          Shelest.onTiming('restore', Date.now() - t2);
          container.style.opacity = '1';
          reportPlace();
          var calls = readyCalls; readyCalls = [];
          for (var i = 0; i < calls.length; i++) calls[i]();
        }

        // Where we are, in characters — the only thing worth preserving across a restyle, since
        // the page it lands on afterwards is a different page.
        window.captureProgress = function() { return currentChar(); };

        window.restoreProgress = function(c) {
          measurePages();
          goToChar(c, false);
        };

        var viewportFrame = 0;
        function scheduleViewportLayout() {
          if (viewportFrame) cancelAnimationFrame(viewportFrame);
          viewportFrame = requestAnimationFrame(function() {
            viewportFrame = 0;
            if (!paged) return;
            var size = viewportSize();
            if (Math.abs(size.width - pageWidth) < 0.01 && Math.abs(size.height - pageHeight) < 0.01) return;
            // The column box still has its old size, so this is the old reading
            // anchor, not a measurement of already distorted columns.
            var character = currentChar();
            abortCurl();
            settle(currentPage);
            measurePages();
            goToChar(character, false);
            reportPlace();
          });
        }
        window.addEventListener('resize', scheduleViewportLayout);
        if (window.ResizeObserver) new ResizeObserver(scheduleViewportLayout).observe(pageFrame);

        /**
         * Puts page [n] on the screen, by scrolling to it.
         *
         * This used to slide the flow itself — `translateX` on the container, which is as wide
         * as the whole book. On a long book that container is millions of pixels across, past
         * anything the browser will hold as a layer of its own, so every turn fell back to
         * repainting the visible page out of a display list the size of the book. Measured, on a
         * twelve-million-character book: three frames drawn in eight seconds. The page did not
         * stutter, it stopped.
         *
         * A scroll is the same movement asked for in the way a browser is built to answer it —
         * the same way the scrolling reading mode moves, which was fluid on the very book the
         * paged one froze on. The flow now stands still and the window over it moves.
         */
        function setOffset(n) {
          if (slideRaf) { cancelAnimationFrame(slideRaf); slideRaf = 0; }
          mask.scrollLeft = n * pageWidth;
        }

        /**
         * The slide, driven frame by frame instead of by the browser's own smooth scroll.
         *
         * `scrollTo({behavior:'smooth'})` decides its own curve and its own duration from the
         * distance, which for a page-wide jump is neither the quarter-second the page turn has
         * always taken nor the easing the other animations are timed against — it read as a
         * different, looser turn. This is the same movement with the numbers put back.
         */
        var slideRaf = 0;

        function slideTo(x, ms) {
          if (slideRaf) { cancelAnimationFrame(slideRaf); slideRaf = 0; }
          var from = mask.scrollLeft, dist = x - from, began = 0;
          if (!dist) return;
          slideRaf = requestAnimationFrame(function step(t) {
            if (!began) began = t;
            var k = Math.min(1, (t - began) / ms);
            // Quick away, gentle in — the shape of the cubic-bezier the transform used.
            var eased = 1 - Math.pow(1 - k, 3);
            mask.scrollLeft = from + dist * eased;
            if (k < 1) { slideRaf = requestAnimationFrame(step); return; }
            slideRaf = 0;
            mask.scrollLeft = x;
          });
        }

        // Puts the page back in a known state: whichever animation was mid-flight, the
        // offset is correct and nothing is left rotated or half-faded. Every animated
        // path finishes here, and a fast tapper who outruns one animation with the next
        // lands here too.
        function settle(n) {
          if (animTimer) { clearTimeout(animTimer); animTimer = null; }
          container.style.transition = 'none';
          setOffset(n);
          container.style.opacity = '1';
          pageFrame.style.transition = 'none';
          pageFrame.style.transform = 'none';
          abortCurl();
          container.offsetHeight;
        }

        function goToPage(n, animate) {
          var turnBegan = Date.now();
          if (n < 0) {
            if (canOpenPrevious) Shelest.onBoundary(-1);
            return;
          }
          if (n >= totalPages) {
            if (canOpenNext) Shelest.onBoundary(1);
            return;
          }
          n = Math.max(0, Math.min(n, totalPages - 1));
          var prev = currentPage;
          currentPage = n;

          if (!animate || animMode === 'none' || prev === n) {
            settle(n);
          } else if (animMode === 'fade') {
            // The page doesn't move — it dissolves. Cheapest of the three and the one
            // that never induces motion sickness.
            if (animTimer) clearTimeout(animTimer);
            container.style.transition = 'opacity 0.13s linear';
            container.style.opacity = '0';
            animTimer = setTimeout(function() {
              animTimer = null;
              container.style.transition = 'none';
              setOffset(n);
              container.offsetHeight;
              container.style.transition = 'opacity 0.19s linear';
              container.style.opacity = '1';
            }, 130);
          } else if (animMode === 'flip') {
            // A half turn of paper: the viewport itself tilts away on the hinge the page
            // is turning from, swaps underneath, and tilts back.
            if (animTimer) clearTimeout(animTimer);
            var forward = n > prev;
            // Animate the clipped frame, not the scrolling element. Transforming
            // the scrollport itself can roll its compositor offset back mid-turn.
            pageFrame.style.transformOrigin = forward ? 'left center' : 'right center';
            pageFrame.style.transition = 'transform 0.15s ease-in';
            pageFrame.style.transform = 'perspective(1400px) rotateY(' + (forward ? -17 : 17) + 'deg)';
            animTimer = setTimeout(function() {
              animTimer = null;
              container.style.transition = 'none';
              setOffset(n);
              container.offsetHeight;
              pageFrame.style.transition = 'transform 0.2s ease-out';
              pageFrame.style.transform = 'none';
            }, 150);
          } else if (animMode === 'curl' && !curlActive) {
            // A tap runs the same fold a finger would drag, from one end to the other,
            // grabbed at mid-height because a tap has no grab point and should look like
            // the plain vertical fold a tap looks like in a real book.
            //
            // The turn does not happen here: the page stays where it was and the app
            // reports back through curlLand() when the fold has finished. That is what
            // makes a fold that gets pulled back leave nothing behind.
            if (animTimer) { clearTimeout(animTimer); animTimer = null; }
            currentPage = prev;
            curlActive = true;
            Shelest.onCurlBegin(prev, n, window.innerHeight / 2, pageWidth, true);
            return;
          } else {
            // Slide. The browser's own smooth scroll, for the same reason the jump is a scroll:
            // it moves the window rather than the book, and it is the one path here that is
            // given tiles ahead of itself instead of being asked to repaint from scratch.
            if (animTimer) { clearTimeout(animTimer); animTimer = null; }
            container.style.opacity = '1';
            container.style.transition = 'opacity 0.15s ease-in';
            slideTo(n * pageWidth, 220);
          }
          // The page number goes at once; the place follows when the turning stops. -1 is not a
          // percentage, it is the absence of one, and the app knows to answer it with the page.
          Shelest.onPageChange(currentPage, totalPages, -1, positionSegment);
          reportExactPosition(-1);
          Shelest.onTiming('turn', Date.now() - turnBegan);
          watchTurn();
          schedulePlace();
        }

        // Live, like every other appearance setting — no reload to try one out.
        window.setPageAnimation = function(m) {
          animMode = m;
          settle(currentPage);
        };

        window.jumpToPage = function(n) {
          goToPage(n, true);
        };

        // One step of hands-free reading; Kotlin decides the pace, the page only
        // knows what "forward" means in the current paging mode.
        window.autoAdvance = function() {
          goToPage(currentPage + 1, true);
        };

        // Columns can't creep: in paged mode Kotlin runs the clock and calls
        // autoAdvance(), so there is no continuous scroll to start here.
        window.setAutoScroll = function(pxPerSec) { };

        // Brings any element in the flow onto the visible page.
        window.revealElement = function(el) {
          var rect = el.getBoundingClientRect();
          var page = Math.floor((rect.left + currentPage * pageWidth) / pageWidth);
          goToPage(page, true);
        };

        // The opening words of the page in view, for the voice to find its place by.
        //
        // The chapter is one long column that the viewport slides along, so "what is on
        // this page" is a question about geometry rather than markup: an element's page is
        // where its left edge lands once the current offset is added back. The first block
        // that *starts* on this page is where the eye starts too — a paragraph running on
        // from the page before has already been read. If nothing starts here, the page is
        // the middle of one long paragraph, and that paragraph is the answer.
        window.pageStartText = function() {
          var els = container.querySelectorAll('p, h1, h2, h3, h4, h5, h6, blockquote, li, .verse-line');
          var fallback = '';
          for (var i = 0; i < els.length; i++) {
            var t = els[i].innerText;
            if (!t || !t.trim()) continue;
            var rect = els[i].getBoundingClientRect();
            // +2 absorbs the sub-pixel rounding that would otherwise put an element sitting
            // exactly on the fold onto the page before it.
            var page = Math.floor((rect.left + currentPage * pageWidth + 2) / pageWidth);
            if (page >= currentPage) return t.trim().slice(0, 160);
            fallback = t.trim().slice(0, 160);
          }
          return fallback;
        };

        window.jumpToHeading = function(text) {
          var els = container.querySelectorAll('h1, h2, h3, h4, .title, .subtitle');
          for (var i = 0; i < els.length; i++) {
            if (els[i].innerText.trim().indexOf(text) !== -1) {
              var rect = els[i].getBoundingClientRect();
              var page = Math.floor((rect.left + currentPage * pageWidth) / pageWidth);
              goToPage(page, true);
              break;
            }
          }
        };

        // The book's own contents pointing at the anchor the chapter was assembled under. The
        // same arithmetic as everywhere else: what is on screen is offset by the page showing,
        // so adding it back gives the position in the whole flow.
        window.jumpToAnchor = function(id) {
          var el = document.getElementById(id);
          if (!el) return;
          var rect = el.getBoundingClientRect();
          goToPage(Math.floor((rect.left + currentPage * pageWidth + 2) / pageWidth), true);
        };

        var isSwiping = false;
        var tx = 0, ty = 0;
        var touchStartedAt = 0;
        var singleTouch = false;
        var chromeTapHandled = false;
        var dragArmed = false;
        // Movement needed before a drag counts as one; subtracted back out afterwards so
        // the fold starts under the finger instead of jumping to meet it. Small on
        // purpose: the picture of the page was taken at touch-down, so there is nothing
        // left to hide behind a long threshold, and three pixels is below what a finger
        // holds still to anyway.
        var DRAG_SLOP = 3;
        var curlFwd = true;

        document.addEventListener('touchstart', function(e) {
          if (!e.touches.length) return;
          tx = e.touches[0].clientX;
          ty = e.touches[0].clientY;
          touchStartedAt = Date.now();
          singleTouch = e.touches.length === 1;
          chromeTapHandled = false;
          isSwiping = false;
          dragArmed = (animMode === 'curl' && e.touches.length === 1 &&
                       !window.getSelection().toString().trim());
          // Taking the picture costs tens of milliseconds and this is the one moment
          // where they cannot be seen: the finger is down and has not moved yet.
          if (dragArmed) Shelest.onCurlArm(currentPage);
        }, {passive:true});

        // Dragging the fold. Not a swipe that plays an animation afterwards — the sheet
        // is held at the finger, and let go it either falls over or springs back,
        // whichever it is closer to. Every position in the range is a valid frame, which
        // is why this can report a raw finger position and nothing else.
        document.addEventListener('touchmove', function(e) {
          if (e.touches.length !== 1) singleTouch = false;
          if (!dragArmed || e.touches.length !== 1) return;
          var dx = e.touches[0].clientX - tx;
          var dy = e.touches[0].clientY - ty;

          if (!curlDrag) {
            if (Math.abs(dx) < DRAG_SLOP || Math.abs(dx) <= Math.abs(dy)) return;
            // A long-press that grew a selection is dragging the selection handles, not
            // the page. Checked here rather than on a stopwatch at touch-down: resting a
            // finger for a second and then pulling is still a page turn.
            if (window.getSelection().toString().trim()) { dragArmed = false; return; }
            var to = (dx < 0) ? currentPage + 1 : currentPage - 1;
            if (to < 0 || to > totalPages - 1) { dragArmed = false; return; }
            curlDrag = true;
            curlActive = true;
            curlFwd = (to > currentPage);
            isSwiping = true;   // so the click that follows doesn't turn a second page
            // Grab height = where the finger went down. Pull near the top and you peel
            // the top corner; pull across the middle and the fold stays upright.
            Shelest.onCurlBegin(currentPage, to, ty, pageWidth, false);
          }

          e.preventDefault();
          // The corner tracks the finger one-to-one from wherever this sheet's corner
          // starts out — at rest against the right edge going forward, already turned
          // away off the left going back. The slop is added back so the fold begins
          // under the finger instead of jumping to meet it, and there is no gain factor:
          // the paper is under your finger, not running ahead of it.
          var travel = dx + (dx < 0 ? DRAG_SLOP : -DRAG_SLOP);
          var originX = curlFwd ? pageWidth : -pageWidth;
          Shelest.onCurlMove(originX + travel, ty + dy);
        }, {passive:false});

        document.addEventListener('touchend', function(e) {
          if (!e.changedTouches.length) return;
          if (curlDrag) {
            curlDrag = false;
            dragArmed = false;
            // Whether it turns, and how it winds down, is decided on the side that has
            // the frame clock and the finger's speed in the units it draws in.
            Shelest.onCurlEnd();
            return;
          }
          var dx = e.changedTouches[0].clientX - tx;
          var dy = e.changedTouches[0].clientY - ty;
          if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 30) {
            isSwiping = true;
            if (dx < 0) goToPage(currentPage + 1, true);
            else        goToPage(currentPage - 1, true);
            return;
          }
          // WebView can drop its compatibility click after scrolling/turning.
          // A fresh centre tap must not depend on that click. Ignore long presses,
          // selection handles, links and annotation taps, and swallow only the
          // duplicate click belonging to the touch we have already handled.
          var x = e.changedTouches[0].clientX;
          var target = e.target && e.target.closest ? e.target.closest('.shelest-ann,a') : null;
          if (singleTouch && !e.touches.length && Math.abs(dx) < 10 && Math.abs(dy) < 10 &&
              Date.now() - touchStartedAt < 350 && x >= window.innerWidth * 0.25 &&
              x <= window.innerWidth * 0.75 && !target && !window.getSelection().toString().trim()) {
            chromeTapHandled = true;
            Shelest.onToggleChrome();
          }
          // A plain tap, or a finger put down and lifted again. Nothing turned, so this is
          // only a nudge to make sure both pictures exist before the next gesture — it
          // costs nothing when they already do, which is nearly always.
          Shelest.onCurlIdle(currentPage);
        }, {passive:true});

        // A finger lifted by the system (call, notification) still has to put the sheet
        // somewhere, or it stays frozen mid-fold over the text.
        document.addEventListener('touchcancel', function() {
          singleTouch = false;
          chromeTapHandled = false;
          dragArmed = false;
          if (!curlDrag) { Shelest.onCurlIdle(currentPage); return; }
          curlDrag = false;
          Shelest.onCurlEnd();
        }, {passive:true});

        document.addEventListener('click', function(e) {
          if (chromeTapHandled) { chromeTapHandled = false; return; }
          if (isSwiping) {
            isSwiping = false;
            return;
          }
          if (window.getSelection().toString().trim()) return;
          var x = e.clientX, w = window.innerWidth;
          if (x < w * 0.25)      goToPage(currentPage - 1, true);
          else if (x > w * 0.75) goToPage(currentPage + 1, true);
          else                    Shelest.onToggleChrome();
        });

        // What has to be in place before the columns can be measured, and no more than that.
        //
        // The text and the fonts, always: a font that swaps in afterwards re-measures every line
        // in the book, which is the whole layout done twice and a page count that changes under
        // the reader.
        //
        // The pictures, only when the document does not say how large they are. It usually does
        // now, and when it does, waiting for them is waiting for every illustration in the book
        // to be fetched and decoded — hundreds of them, for a page nobody is looking at — before
        // the first word appears.
        var waitForImages = $waitForImages;
        var readingFace = "$fontLoad";

        function settlePaging() {
          if (window.readerResourcesReady) {
            var resources = window.readerResourcesReady;
            window.readerResourcesReady = null;
            resources.then(settlePaging);
            return;
          }
          Shelest.onTiming('pictures waited for', Math.round(performance.now()));
          // The face has to be asked for, not merely waited on: a webfont is fetched only once
          // something needs it, so waiting on fonts.ready by itself resolves against an empty
          // queue, lays the book out in the fallback serif, and lays it out again.
          var waiting = null;
          try {
            if (readingFace && document.fonts && document.fonts.load) {
              waiting = document.fonts.load('400 1em ' + readingFace);
            }
          } catch (e) { waiting = null; }
          if (waiting && waiting.then) waiting.then(function() { afterInitialLayout(initPaging); });
          else setTimeout(function() { afterInitialLayout(initPaging); }, 80);
          // Neither a font that never arrives nor a promise that never settles may cost the
          // reader the book.
          setTimeout(function() { if (!paged) afterInitialLayout(initPaging); }, 1500);
        }

        // The reader calls this from onPageFinished as well, because a WebView does not
        // reliably deliver 'load' for a document served through its own interceptor. It still
        // has to respect the wait, or an illustrated book would be measured with every picture
        // in it still zero pixels tall.
        /**
         * The pictures have arrived — or the book has been opened without them.
         *
         * Waiting for every illustration in a book before showing a word of it is a wait with no
         * ceiling: they are fetched one at a time, out of the book's own file, and a long
         * illustrated book has hundreds. So the wait is capped. If the columns had to be measured
         * around holes that have since filled, they are measured again here — and because the
         * reader's place is a character and not a page, the same word stays under the eye.
         */
        function imagesIn() {
          if (!paged) { settlePaging(); return; }
          if (window.restoreProgress) restoreProgress(currentChar());
        }

        window.startPaging = function() {
          if (waitForImages && document.readyState !== 'complete') {
            window.addEventListener('load', imagesIn);
            setTimeout(function() { if (!paged) settlePaging(); }, 2500);
            return;
          }
          settlePaging();
        };

        if (waitForImages) window.addEventListener('load', imagesIn);
        else if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', settlePaging);
        else settlePaging();
    """.trimIndent() else """
        Shelest.onTiming('document read', Math.round(performance.now()));
        var container = document.getElementById('container');
        var savedProgress = $savedProgress;
        var canOpenPrevious = $hasPrevious;
        var canOpenNext = $hasNext;
        var placed = false;
        var readyCalls = [];
        window.runWhenReaderReady = function(call) {
          if (placed) call(); else readyCalls.push(call);
        };

        var lastSelection = "";
        document.addEventListener('selectionchange', function() {
          var s = window.getSelection().toString().trim();
          if (s) { lastSelection = s; }
        });

        function getSelectedText() {
          var s = window.getSelection().toString().trim();
          if (!s && document.getSelection) s = document.getSelection().toString().trim();
          if (!s && lastSelection) s = lastSelection;
          return s ? s : "";
        }

        var lastReportedPage = -1;

        function updateScrollPage() {
          if (!placed) return;
          var h = window.innerHeight;
          var docHeight = document.documentElement.scrollHeight;
          var maxScroll = Math.max(1, docHeight - h);
          var totalPages = Math.max(1, Math.ceil(docHeight / h));
          var curPage = Math.min(totalPages - 1, Math.round((window.scrollY / maxScroll) * (totalPages - 1)));
          reportExactPosition(-1, curPage);
          schedulePlace();
          // Only when the page number actually changes: scroll fires every frame, and
          // during a continuous auto-scroll that would push a state update across the
          // JS bridge 60 times a second for a number that hasn't moved.
          if (curPage !== lastReportedPage) {
            lastReportedPage = curPage;
            lastTotalPages = totalPages;
            // The screenful goes at once; where in the book it is follows when the scrolling
            // stops. Working that out is a search through every block of the book, and doing it
            // for each screenful of a continuous crawl is a stutter once a screenful.
            Shelest.onPageChange(curPage, totalPages, -1, positionSegment);
          }
        }

        var placeTimer = null;
        var lastTotalPages = 1;

        function reportPlace() {
          if (!placed) return -1;
          if (placeTimer) { clearTimeout(placeTimer); placeTimer = null; }
          var t0 = Date.now();
          var c = currentChar();
          var t = totalChars();
          var pct = t > 0 ? Math.min(100, Math.max(0, c * 100 / t)) : 0;
          reportExactPosition(c, Math.max(0, lastReportedPage));
          Shelest.onTiming('place', Date.now() - t0);
          Shelest.onPageChange(Math.max(0, lastReportedPage), lastTotalPages, pct, positionSegment);
          return c;
        }

        function schedulePlace() {
          if (placeTimer) clearTimeout(placeTimer);
          placeTimer = setTimeout(reportPlace, 600);
        }

        /** Whatever is owed, paid now: the reader is leaving. */
        window.flushPlace = function() { if (placeTimer) reportPlace(); };

        document.addEventListener('visibilitychange', function() {
          if (document.hidden) flushPlace();
        });
        window.addEventListener('pagehide', function() { flushPlace(); });

        // How far down the document a character sits, in pixels from the top of the book.
        function yOfChar(c) {
          var rect = rectAtChar(c);
          return rect ? Math.round(rect.top + window.scrollY) : Math.round(window.scrollY);
        }

        /** The first character on screen. */
        function currentChar() { return charAtPlace(Math.round(window.scrollY), yOfChar); }

        function goToChar(c, smooth) {
          var y = Math.max(0, yOfChar(c));
          if (smooth) window.scrollTo({ top: y, behavior: 'smooth' });
          else window.scrollTo(0, y);
        }

        /** How far into the book, as a percentage of its characters. */
        function progressPercent() {
          var c = currentChar();
          if (c <= 0) return 0;
          var t = totalChars();
          return t > 0 ? Math.min(100, Math.max(0, c * 100 / t)) : 0;
        }

        function initPaging() {
          // The saved place is a percentage of the book's characters, not of the document's
          // height — so it survives a change of font, of size, of margins or of screen, none of
          // which leave the height it was measured against standing.
          Shelest.onTiming('before first layout', Math.round(performance.now()));
          var exactScroll = initialReadingPosition && initialReadingPosition.layout === positionLayout();
          var anchor = initialReadingPosition ? initialReadingPosition.character : -1;
          if (exactScroll) {
            window.scrollTo(0, initialReadingPosition.scrollY);
          } else if (anchor >= 0 || savedProgress > 0) {
            var t1 = Date.now();
            var t = totalChars();
            Shelest.onTiming('index', Date.now() - t1);
            var t2 = Date.now();
            if (anchor >= 0 && t > 0) goToChar(Math.min(anchor, t - 1), false);
            else if (t > 0) goToChar(Math.max(0, Math.min(t - 1, Math.round(savedProgress / 100.0 * t))), false);
            Shelest.onTiming('restore', Date.now() - t2);
          }
          updateScrollPage();
          reportPlace();
          var calls = readyCalls; readyCalls = [];
          for (var i = 0; i < calls.length; i++) calls[i]();
        }

        window.jumpToPage = function(n) {
          var h = window.innerHeight;
          var docHeight = document.documentElement.scrollHeight;
          var totalPages = Math.max(1, Math.ceil(docHeight / h));
          n = Math.max(0, Math.min(n, totalPages - 1));
          var targetY = Math.round((n / Math.max(1, totalPages - 1)) * Math.max(0, docHeight - h));
          window.scrollTo({ top: targetY, behavior: 'smooth' });
        };

        // Hands-free reading in scroll mode: a screenful minus a couple of lines,
        // so the sentence the eye is on doesn't vanish off the top mid-read.
        window.autoAdvance = function() {
          window.scrollBy({ top: Math.round(window.innerHeight * 0.88), behavior: 'smooth' });
        };

        // Continuous crawl — what auto-scroll should feel like when the text is one
        // long column: jumping a screenful every N seconds loses your place, so the
        // page creeps instead.
        //
        // The position is kept as a float and written with scrollTo, not stepped with
        // an integer scrollBy: at a readable pace a whole pixel is several frames apart,
        // so integer steps stand still and then twitch. Blink scrolls at sub-pixel
        // precision, which turns the same speed into an even drift.
        var autoRaf = null, autoPx = 0, autoLast = 0, autoY = 0, autoWrote = -1;

        function autoStep(ts) {
          if (autoPx <= 0) { autoRaf = null; return; }
          // First frame, or the reader dragged the page themselves — take their
          // position as the new truth instead of yanking back to ours.
          if (!autoLast || Math.abs(window.scrollY - autoWrote) > 2) {
            autoY = window.scrollY;
            autoLast = ts;
          }
          // A backgrounded tab can hand us a huge gap; clamp it so returning to the
          // book doesn't teleport past a page.
          var dt = Math.min(64, ts - autoLast);
          autoLast = ts;
          autoY += autoPx * dt / 1000;
          var max = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
          if (autoY >= max) {
            window.scrollTo(0, max);
            autoPx = 0; autoRaf = null;
            if (canOpenNext) Shelest.onBoundary(1);
            return;
          }
          window.scrollTo(0, autoY);
          autoWrote = window.scrollY;
          autoRaf = requestAnimationFrame(autoStep);
        }

        window.setAutoScroll = function(pxPerSec) {
          autoPx = pxPerSec;
          autoLast = 0;
          if (autoRaf) { cancelAnimationFrame(autoRaf); autoRaf = null; }
          if (autoPx > 0) autoRaf = requestAnimationFrame(autoStep);
        };

        // In characters, not in pixels of scroll: a restyle is exactly the moment the document's
        // height stops meaning what it meant, and the position has to outlive it.
        window.captureProgress = function() { return currentChar(); };

        window.restoreProgress = function(c) {
          goToChar(c, false);
          autoLast = 0;              // let the crawl pick up from where the text now is
          lastReportedPage = -1;
          updateScrollPage();
        };

        window.revealElement = function(el) {
          el.scrollIntoView({ behavior: 'smooth', block: 'center' });
        };

        // The opening words of what is on screen, for the voice to find its place by.
        // Scrolling makes this the easy case: the first block whose bottom has not yet
        // passed the top of the window is the one being read.
        window.pageStartText = function() {
          var els = container.querySelectorAll('p, h1, h2, h3, h4, h5, h6, blockquote, li, .verse-line');
          var fallback = '';
          for (var i = 0; i < els.length; i++) {
            var t = els[i].innerText;
            if (!t || !t.trim()) continue;
            if (els[i].getBoundingClientRect().bottom > 0) return t.trim().slice(0, 160);
            fallback = t.trim().slice(0, 160);
          }
          return fallback;
        };

        // Nothing to animate: in scroll mode there are no page turns, only scrolling.
        window.setPageAnimation = function(m) { };

        window.jumpToHeading = function(text) {
          var els = container.querySelectorAll('h1, h2, h3, h4, .title, .subtitle');
          for (var i = 0; i < els.length; i++) {
            if (els[i].innerText.trim().indexOf(text) !== -1) {
              els[i].scrollIntoView({ behavior: 'smooth' });
              break;
            }
          }
        };

        // The book's own contents pointing at the anchor the chapter was assembled under.
        window.jumpToAnchor = function(id) {
          var el = document.getElementById(id);
          if (el) el.scrollIntoView({ behavior: 'smooth' });
        };

        // At a segment edge the first gesture gets the reader to the last (or first) line;
        // a second outward swipe crosses the boundary. This leaves time to read that line and
        // mirrors the physical action used everywhere else in the book.
        var edgeTouchY = 0;
        document.addEventListener('touchstart', function(e) {
          if (e.touches.length === 1) edgeTouchY = e.touches[0].clientY;
        }, {passive:true});
        document.addEventListener('touchend', function(e) {
          if (!e.changedTouches.length) return;
          var dy = e.changedTouches[0].clientY - edgeTouchY;
          var max = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
          if (dy < -35 && window.scrollY >= max - 2 && canOpenNext) Shelest.onBoundary(1);
          else if (dy > 35 && window.scrollY <= 2 && canOpenPrevious) Shelest.onBoundary(-1);
        }, {passive:true});

        document.addEventListener('click', function(e) {
          if (window.getSelection().toString().trim()) return;
          Shelest.onToggleChrome();
        });

        window.addEventListener('scroll', updateScrollPage, {passive:true});
        // As in the paged reader: the saved position is a fraction of the document's height, so
        // the height has to be settled before it can be restored — but a document that states the
        // size of its pictures is settled without waiting for a single one of them to arrive.
        var waitForImages = $waitForImages;
        var readingFace = "$fontLoad";
        function place() {
          if (placed) return;
          afterInitialLayout(function() {
            if (placed) return;
            placed = true;
            initPaging();
          });
        }

        function settlePaging() {
          if (window.readerResourcesReady) {
            var resources = window.readerResourcesReady;
            window.readerResourcesReady = null;
            resources.then(settlePaging);
            return;
          }
          Shelest.onTiming('pictures waited for', Math.round(performance.now()));
          var waiting = null;
          try {
            if (readingFace && document.fonts && document.fonts.load) {
              waiting = document.fonts.load('400 1em ' + readingFace);
            }
          } catch (e) { waiting = null; }
          if (waiting && waiting.then) waiting.then(place);
          else setTimeout(place, 80);
          setTimeout(place, 1500);
        }

        // Capped, and for the same reason as in the paged reader: a book's pictures arrive one
        // at a time and there may be hundreds of them. Placed early, the document is placed
        // again when they land — by character, so the same word stays under the eye.
        function imagesIn() {
          if (!placed) { settlePaging(); return; }
          if (window.restoreProgress) restoreProgress(currentChar());
        }

        window.startPaging = function() {
          if (waitForImages && document.readyState !== 'complete') {
            window.addEventListener('load', imagesIn);
            setTimeout(function() { if (!placed) settlePaging(); }, 2500);
            return;
          }
          settlePaging();
        };

        if (waitForImages) window.addEventListener('load', imagesIn);
        else if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', settlePaging);
        else settlePaging();
    """.trimIndent()

    // Shared across both paging modes: where the reader is, measured in characters of the
    // book rather than in pages of the screen.
    //
    // A page is a property of the screen it is shown on. Make the type one step larger and a
    // six-hundred-page book becomes a nine-hundred-page book, and "page 210" — which is what the
    // reader's place used to be written down as, as a fraction — now points a hundred pages away
    // from the sentence they left off on. Characters do not move: the four-hundred-thousandth
    // character of a book is the same word in every font, at every size, on every screen, in the
    // paged reader and the scrolling one alike.
    //
    // That is also the measure the windowed reader will need — a piece of a book knows which
    // characters it holds and nothing whatever about pages — so this is the shape the position
    // has to take before the reader can be taken apart.
    val charIndexJs = """
        // Every element that directly holds text, in the order the book reads, with the character
        // that element starts at. Blocks rather than text nodes on purpose: highlighting a word
        // wraps it in a span, which splits one text node into three without changing a single
        // character of the book — an index of nodes would be stale after every highlight, and an
        // index of blocks is not.
        var BLOCK_SEL = 'p,h1,h2,h3,h4,h5,h6,li,blockquote,pre,figcaption,td,th,dd,dt,div,section,article';
        var charIndex = null;

        // Built once and kept. It holds no geometry — only which block starts where in the text —
        // so changing the font, the margins or the screen leaves it entirely correct, which is the
        // whole reason the position is kept this way.
        function charIndexOf() {
          if (charIndex) return charIndex;
          var walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null);
          var blocks = [], total = 0, node, lastEl = null, entry = null;
          while (node = walker.nextNode()) {
            var len = node.nodeValue.length;
            if (!len) continue;
            var el = node.parentElement;
            el = el ? (el.closest(BLOCK_SEL) || container) : container;
            if (el !== lastEl) {
              lastEl = el;
              entry = { el: el, start: total, len: 0 };
              blocks.push(entry);
            }
            entry.len += len;
            total += len;
          }
          charIndex = { blocks: blocks, total: total };
          return charIndex;
        }

        function totalChars() { return charIndexOf().total; }

        // The text node a character falls in, and how far into it.
        function pointAtChar(c) {
          // Whitespace between paragraphs belongs to the root, not to a paragraph.
          // Walking that root as a block started again at the first paragraph and made
          // the character/page mapping non-monotonic. Count the same raw text used by
          // cloud anchors; highlights may split nodes but never change these offsets.
          var want = Math.max(0, c);
          var w = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null), n, local = 0;
          while (n = w.nextNode()) {
            var len = n.nodeValue.length;
            if (!len) continue;
            if (local + len > want && n.nodeValue.trim()) return { node: n, offset: Math.max(0, want - local), el: n.parentElement };
            local += len;
          }
          return null;
        }

        // Where a character sits on screen. A collapsed range gives a caret-thin rectangle, which
        // is all the arithmetic below needs; where there is no text to put a caret in — a picture
        // standing on its own — the block it belongs to answers instead.
        function rectAtChar(c) {
          var p = pointAtChar(c);
          if (!p) return null;
          if (p.node) {
            try {
              var off = Math.min(p.offset, p.node.nodeValue.length);
              var r = document.createRange();
              r.setStart(p.node, off);
              r.setEnd(p.node, off);
              var rect = r.getBoundingClientRect();
              if (rect && (rect.width || rect.height || rect.top || rect.left)) return rect;
            } catch (e) { }
          }
          return p.el ? p.el.getBoundingClientRect() : null;
        }

        // The first character at or past a given place in the flow.
        //
        // `placeOf(c)` answers where a character sits — which page, in the paged reader; how many
        // pixels down, in the scrolling one — and through a book it never goes backwards. That is
        // what makes this a search rather than a count: some twenty measurements answer it for a
        // book of any length, where counting would be one per paragraph on every page turn.
        function charAtPlace(want, placeOf) {
          // Before the index, not after: the start of the book is the start of the book, and a
          // reader who has not moved off page one should not pay for a walk of the whole
          // document to be told so.
          if (want <= 0) return 0;
          var idx = charIndexOf();
          if (!idx.total || !idx.blocks.length) return 0;
          var lo = 0, hi = idx.blocks.length - 1, found = -1;
          while (lo <= hi) {
            var mid = (lo + hi) >> 1;
            if (placeOf(idx.blocks[mid].start) >= want) { found = mid; hi = mid - 1; }
            else lo = mid + 1;
          }
          if (found < 0) {
            // Nothing begins this late, so this is somewhere inside the last block — the closing
            // paragraph of a book that runs on for pages — or genuinely past the end, which the
            // search below answers with the end.
            var last = idx.blocks[idx.blocks.length - 1];
            return last.start + offsetInBlock(last, want, placeOf);
          }
          var entry = idx.blocks[found];
          if (found === 0 || placeOf(entry.start) === want) return entry.start;
          // Nothing begins here either, so this is the middle of one long block and the place has
          // to be found inside it.
          return idx.blocks[found - 1].start + offsetInBlock(idx.blocks[found - 1], want, placeOf);
        }

        /**
         * The first character inside one block that is at or past [want], counted from the
         * block's own start; its length if nothing in it is that late.
         *
         * The blocks butt up against each other, so answering with the block's own end is
         * answering with where the next one begins — which is the right answer when the place
         * being looked for is past everything this block holds.
         */
        function offsetInBlock(block, want, placeOf) {
          var lo = 0, hi = block.len, best = block.len;
          while (lo <= hi) {
            var mid = (lo + hi) >> 1;
            if (placeOf(block.start + mid) >= want) { best = mid; hi = mid - 1; }
            else lo = mid + 1;
          }
          return best;
        }
    """.trimIndent()

    // Shared across both paging modes: text-anchored highlight/note annotations for
    // HTML content. Appended (not interpolated) so trimIndent() on scriptJs/annotationJs
    // above stays independent of each other's indentation.
    val annotationJs = """
        var appliedAnnEls = [];

        function buildTextMap() {
          var walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null);
          var map = [];
          var text = '';
          var node;
          while (node = walker.nextNode()) {
            var p = node.parentNode;
            if (p && p.closest && p.closest('script,style')) continue;
            map.push({node: node, start: text.length});
            text += node.nodeValue;
          }
          return {map: map, text: text};
        }

        function offsetToPoint(map, offset) {
          for (var i = map.length - 1; i >= 0; i--) {
            if (offset >= map[i].start) {
              return {node: map[i].node, offset: offset - map[i].start};
            }
          }
          return null;
        }

        function getSelectionContext() {
          var text = window.getSelection().toString().trim();
          if (!text && lastSelection) text = lastSelection;
          if (!text) return {selectedText: '', contextBefore: '', contextAfter: ''};
          var tm = buildTextMap();
          var idx = tm.text.indexOf(text);
          if (idx === -1) return {selectedText: text, contextBefore: '', contextAfter: ''};
          var before = tm.text.substring(Math.max(0, idx - 40), idx);
          var after = tm.text.substring(idx + text.length, idx + text.length + 40);
          return {selectedText: text, contextBefore: before, contextAfter: after};
        }

        // Capture the actual range, not text.indexOf(selectedWord): the same word
        // can occur hundreds of times. Include context to align DOM text with TTS.
        window.getSpeechContext = function(selected) {
          var tm = buildTextMap(), at = currentChar();
          if (selected) {
            var sel = window.getSelection();
            if (!sel || !sel.rangeCount || sel.isCollapsed) return null;
            var r = sel.getRangeAt(0);
            if (!container.contains(r.startContainer)) return null;
            var prefix = document.createRange();
            prefix.selectNodeContents(container);
            prefix.setEnd(r.startContainer, r.startOffset);
            at = prefix.toString().length;
          }
          while (at < tm.text.length && /\\s/.test(tm.text[at])) at++;
          return {at:at, text:tm.text.slice(at,at+240), before:tm.text.slice(Math.max(0,at-120),at)};
        };

        function hexToRgba(colorInt, alpha) {
          var r = (colorInt >> 16) & 255, g = (colorInt >> 8) & 255, b = colorInt & 255;
          return 'rgba(' + r + ',' + g + ',' + b + ',' + alpha + ')';
        }

        function clearAnnotations() {
          for (var i = 0; i < appliedAnnEls.length; i++) {
            var el = appliedAnnEls[i];
            if (el && el.parentNode) {
              var parent = el.parentNode;
              while (el.firstChild) parent.insertBefore(el.firstChild, el);
              parent.removeChild(el);
              parent.normalize();
            }
          }
          appliedAnnEls = [];
        }

        function applyOneAnnotation(ann) {
          if (!ann.selectedText) return;
          var tm = buildTextMap();
          var needle = (ann.contextBefore || '') + ann.selectedText + (ann.contextAfter || '');
          var idx = needle ? tm.text.indexOf(needle) : -1;
          var start;
          if (idx !== -1) {
            start = idx + (ann.contextBefore || '').length;
          } else {
            start = tm.text.indexOf(ann.selectedText);
            if (start === -1) return;
          }
          var end = start + ann.selectedText.length;
          var startPoint = offsetToPoint(tm.map, start);
          // A selection ending exactly at a paragraph boundary belongs to the
          // preceding text node, not offset zero in the next paragraph. Otherwise
          // surroundContents sees a partially selected block and drops the note.
          var endPoint = offsetToPoint(tm.map, end - 1);
          if (endPoint) endPoint.offset++;
          if (!startPoint || !endPoint) return;
          try {
            var fragments = tm.map.filter(function(p) { return p.start + p.node.nodeValue.length > start && p.start < end; });
            // Wrap individual text nodes: surroundContents on a multi-element range
            // throws for paragraphs and for partially overlapping older highlights.
            for (var f = fragments.length - 1; f >= 0; f--) {
            var point = fragments[f], range = document.createRange();
            range.setStart(point.node, Math.max(0,start-point.start));
            range.setEnd(point.node, Math.min(point.node.nodeValue.length,end-point.start));
            var el = document.createElement(ann.type === 'NOTE' ? 'span' : 'mark');
            el.className = 'shelest-ann';
            el.dataset.id = ann.id;
            if (ann.type === 'NOTE') {
              el.style.borderBottom = '2px dotted ' + hexToRgba(ann.color, 0.9);
              el.style.cursor = 'pointer';
            } else {
              el.style.background = 'color-mix(in srgb, ' + hexToRgba(ann.color,1) + ' 38%, var(--bg))';
              el.style.borderRadius = '2px';
              el.style.cursor = 'pointer';
            }
            range.surroundContents(el);
            appliedAnnEls.push(el);
            }
          } catch (e) { /* range crosses an element boundary — skip this one */ }
        }

        window.applyAnnotations = function(list) {
          // The spoken mark goes first. Annotations are anchored by counting characters
          // through the document, and an element wrapped around part of a paragraph is one
          // more boundary for a note's range to straddle — it would be dropped, and a note
          // that vanishes because the voice happened to be on that line is a bug that only
          // shows up while listening. The mark comes back on the next sentence.
          clearSpokenMark();
          clearAnnotations();
          for (var i = 0; i < list.length; i++) applyOneAnnotation(list[i]);
        };

        // ── Search hit ────────────────────────────────────────────────────────
        // Landing on the right page and leaving the reader to find the word again is
        // only half a search, so the exact occurrence is wrapped, flashed and brought
        // into view. The page does the finding: it is the only side that knows where
        // the word ended up under the current font size and margins.
        var searchHitEl = null;

        function clearSearchHit() {
          if (searchHitEl && searchHitEl.parentNode) {
            var p = searchHitEl.parentNode;
            while (searchHitEl.firstChild) p.insertBefore(searchHitEl.firstChild, searchHitEl);
            p.removeChild(searchHitEl);
            p.normalize();
          }
          searchHitEl = null;
        }

        window.findAndHighlight = function(query, nth) {
          clearSearchHit();
          if (!query) return;
          var tm = buildTextMap();
          var hay = tm.text.toLowerCase();
          var needle = query.toLowerCase();
          var start = -1, from = 0;
          for (var i = 0; i <= nth; i++) {
            start = hay.indexOf(needle, from);
            if (start === -1) break;
            from = start + needle.length;
          }
          if (start === -1) return;
          var sp = offsetToPoint(tm.map, start);
          var ep = offsetToPoint(tm.map, start + query.length);
          if (!sp || !ep) return;
          try {
            var range = document.createRange();
            range.setStart(sp.node, sp.offset);
            range.setEnd(ep.node, ep.offset);
            var el = document.createElement('mark');
            el.className = 'shelest-hit';
            range.surroundContents(el);
            searchHitEl = el;
            if (window.revealElement) window.revealElement(el);
          } catch (e) { /* the match straddles an element boundary — skip it */ }
        };

        document.addEventListener('click', function(e) {
          var el = e.target && e.target.closest ? e.target.closest('.shelest-ann') : null;
          if (el) {
            e.stopPropagation();
            Shelest.onAnnotationClick(el.dataset.id);
          }
        }, true);

        // ── The line being read aloud ─────────────────────────────────────────
        //
        // The voice knows the book as a numbered list of sentences, cut from the plain text;
        // the page knows it as markup. Neither can point at the other, so the same trick the
        // player already uses to find its place works in reverse here: both sides are reduced
        // to bare letters and digits, and the question becomes a substring search.
        //
        // Searching the whole book for every sentence would mean walking the entire document
        // three times a minute, so the blocks are indexed once and remembered, and the search
        // starts from the block the last sentence was found in. Reading runs forward, so that
        // is almost always the same block or the next one; only a jump pays for a full scan.
        var spokenEl      = null;
        var spokenIsBlock = false;
        var spokenBlocks  = null;
        var spokenCursor  = 0;
        // "A letter or a digit", to match what the Kotlin side strips out.
        //
        // Built from strings inside a try rather than written as a literal on purpose. Unicode
        // property escapes need a WebView from 2018 or later, and a regular expression literal
        // the engine cannot parse is a syntax error in the whole script — not a broken
        // highlight but a blank page, with paging and annotations gone with it. Built this way
        // the failure is contained, and the fallback covers the two alphabets this app is for.
        var LETTER, NOT_LETTER;
        try {
          LETTER     = new RegExp('[\\p{L}\\p{N}]', 'u');
          NOT_LETTER = new RegExp('[^\\p{L}\\p{N}]', 'gu');
        } catch (e) {
          LETTER     = /[0-9A-Za-zА-Яа-яЁё]/;
          NOT_LETTER = /[^0-9A-Za-zА-Яа-яЁё]/g;
        }

        function lettersOnly(s) { return s.toLowerCase().replace(NOT_LETTER, ''); }

        function buildSpokenBlocks() {
          var els = container.querySelectorAll('p, h1, h2, h3, h4, h5, h6, blockquote, li, .verse-line');
          spokenBlocks = [];
          for (var i = 0; i < els.length; i++) {
            // textContent rather than innerText: innerText is defined in terms of layout, so
            // asking every paragraph in a novel for it is a full reflow of the book.
            var norm = lettersOnly(els[i].textContent || '');
            if (norm) spokenBlocks.push({el: els[i], norm: norm});
          }
        }

        /** Letters below which a key means nothing on its own, and position has to decide. */
        var MIN_UNIQUE = 12;
        /** How far past the last marked block a short line is allowed to be looked for. */
        var NEARBY = 3;

        /**
         * The block a sentence *starts with*, for the case the other search cannot answer: a
         * heading with no full stop after it, which the splitter reads as one line running into
         * the paragraph below. No key taken from that line exists in either block — the join is
         * inside it — but the heading is a whole block sitting at the head of the sentence.
         */
        function findSpokenOpening(full, from) {
          for (var i = Math.max(0, from); i < spokenBlocks.length; i++) {
            var n = spokenBlocks[i].norm;
            if (n.length >= 4 && full.indexOf(n) === 0) return i;
          }
          return -1;
        }

        function findSpokenBlock(key, from, until) {
          var last = Math.min(until, spokenBlocks.length);
          for (var i = Math.max(0, from); i < last; i++) {
            if (spokenBlocks[i].norm.indexOf(key) !== -1) return i;
          }
          return -1;
        }

        /** Node and offset of the [target]-th letter inside [el], counting only letters. */
        function letterPoint(el, target) {
          var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
          var seen = 0, node;
          while (node = walker.nextNode()) {
            var v = node.nodeValue;
            for (var i = 0; i < v.length; i++) {
              if (!LETTER.test(v[i])) continue;
              if (seen === target) return {node: node, offset: i};
              seen++;
            }
          }
          return null;
        }

        function clearSpokenMark() {
          if (!spokenEl) return;
          if (spokenIsBlock) {
            spokenEl.classList.remove('shelest-spoken-block');
          } else if (spokenEl.parentNode) {
            var p = spokenEl.parentNode;
            while (spokenEl.firstChild) p.insertBefore(spokenEl.firstChild, spokenEl);
            p.removeChild(spokenEl);
            p.normalize();
          }
          spokenEl = null;
          spokenIsBlock = false;
        }
        window.clearSpoken = clearSpokenMark;

        /**
         * Marks [sentence] where it stands in the text, and — when [follow] — brings it into
         * view, which in paged mode means turning the page the voice has walked onto.
         */
        window.markSpoken = function(sentence, follow) {
          if (!sentence) return;
          if (!spokenBlocks) buildSpokenBlocks();

          var full = lettersOnly(sentence);
          var key  = full.slice(0, 60);
          if (!key) return;

          var at;
          if (key.length >= MIN_UNIQUE) {
            // Sixty letters identify a sentence anywhere in a book, so the search may roam.
            at = findSpokenBlock(key, spokenCursor, spokenBlocks.length);
            if (at < 0) at = findSpokenBlock(key, 0, spokenBlocks.length);
            if (at < 0) {
              // A sentence can span two blocks: a heading with no full stop runs into the
              // paragraph under it and the splitter reads them as one line. The opening
              // survives in the first block, so a shorter key still finds where to start.
              key = key.slice(0, 24);
              at = findSpokenBlock(key, spokenCursor, spokenBlocks.length);
              if (at < 0) at = findSpokenBlock(key, 0, spokenBlocks.length);
            }
            if (at < 0) {
              at = findSpokenOpening(full, spokenCursor);
              if (at < 0) at = findSpokenOpening(full, 0);
            }
          } else {
            // "Он ушёл." — six letters, which would match a hundred places in a novel. Short
            // lines are common in dialogue, so refusing to mark them at all would make the
            // highlight blink out every few seconds. Reading runs forward, so the answer is
            // to trust position instead of the text: look only where the voice already is.
            at = findSpokenBlock(key, spokenCursor, spokenCursor + NEARBY);
          }
          // Nothing found. The previous mark is deliberately left where it is: a stale mark on
          // the paragraph being read is closer to the truth than no mark at all, and this
          // happens on exactly the short lines that sit in the paragraph already marked.
          if (at < 0) return;

          clearSpokenMark();
          spokenCursor = at;
          var block = spokenBlocks[at];
          var from  = block.norm.indexOf(key);
          var to;
          if (from < 0) {
            // Matched as an opening rather than by its text: the whole block is the start of
            // this sentence, so the whole block is what gets marked.
            from = 0;
            to   = block.norm.length;
          } else {
            // Mark the sentence, not the key — but never past the end of the block, which is
            // where a sentence that ran on into the next one would otherwise take us.
            to = Math.min(from + full.length, block.norm.length);
          }

          var sp = letterPoint(block.el, from);
          var ep = letterPoint(block.el, to - 1);
          if (sp && ep) {
            try {
              var range = document.createRange();
              range.setStart(sp.node, sp.offset);
              range.setEnd(ep.node, ep.offset + 1);
              var mark = document.createElement('mark');
              mark.className = 'shelest-spoken';
              range.surroundContents(mark);
              spokenEl = mark;
              spokenIsBlock = false;
            } catch (e) { /* the sentence straddles an italic or a footnote link */ }
          }
          if (!spokenEl) {
            // Whatever could not be wrapped is still readable as a whole paragraph, which is
            // the part of the answer that actually matters: where on the page to look.
            block.el.classList.add('shelest-spoken-block');
            spokenEl = block.el;
            spokenIsBlock = true;
          }
          if (follow && window.revealElement) window.revealElement(spokenEl);
        };

        // ── Live restyle ──────────────────────────────────────────────────────
        // Font, size, spacing, margins and page colour are all custom properties, so
        // changing them never touches the document: the text stays parsed, the
        // annotations stay wrapped, and the reader sees the change on the next frame
        // instead of watching a blank page reload.
        /**
         * The parts of the appearance sheet that change the shape of the flow.
         *
         * Nothing else may cause a re-measure. Re-measuring is laying the whole book out again,
         * which on a long one is a freeze of several seconds — and it was being paid for the
         * colour of the paper and for the choice of page animation, neither of which moves a
         * single word. Trying the four page turns in the settings meant four relayouts of the
         * book, which is exactly what it felt like.
         */
        var LAYOUT_KEYS = ['font', 'fs', 'lh', 'mx', 'align', 'fontLoad'];
        var lastShape = shapeOf($styleJson);

        function shapeOf(s) {
          var out = '';
          for (var i = 0; i < LAYOUT_KEYS.length; i++) out += '' + s[LAYOUT_KEYS[i]];
          return out;
        }

        window.applyStyle = function(s) {
          var shape = shapeOf(s);
          // The first call carries the values the document was already built with, so there is
          // nothing to redo — only something to remember for the next call to differ from.
          var reshapes = lastShape !== null && shape !== lastShape;
          lastShape = shape;
          // Read the position first — after the properties change, the old height and
          // page count are gone and there is nothing left to measure against.
          var frac = reshapes && window.captureProgress ? window.captureProgress() : 0;
          var r = document.documentElement.style;
          r.setProperty('--font',   s.font);
          r.setProperty('--fs',     s.fs);
          r.setProperty('--lh',     s.lh);
          r.setProperty('--mx',     s.mx);
          r.setProperty('--bg',     s.bg);
          r.setProperty('--fg',     s.fg);
          r.setProperty('--accent', s.accent);
          r.setProperty('--sel',    s.sel);
          r.setProperty('--align',  s.align);
          if (s.anim && window.setPageAnimation) window.setPageAnimation(s.anim);
          if (!reshapes) return;
          if (window.restoreProgress) window.restoreProgress(frac);
          // A face used for the first time loads asynchronously; the layout above was
          // measured with the fallback, so measure once more when the real one lands.
          if (s.fontLoad && document.fonts && document.fonts.load) {
            try {
              document.fonts.load('400 1em ' + s.fontLoad).then(function() {
                if (window.restoreProgress) window.restoreProgress(frac);
              });
            } catch (e) { }
          }
        };
    """.trimIndent()

    val positionJs = """
        var initialReadingPosition = $positionJson;
        var positionSegment = $segmentIndex;
        var lastExactPosition = '';
        var initialLayoutScheduled = false;
        function afterInitialLayout(call) {
          if (initialLayoutScheduled) return;
          initialLayoutScheduled = true;
          // Android can fire DOM/font-ready while a newly attached WebView is still
          // 0x0. Never compare its layout ID or restore percentages in that state.
          // Require two stable non-zero samples; hidden measurement frames use timers
          // because their animation frames may be suspended by the browser.
          var previousW = -1, previousH = -1;
          function ready() {
            var w = innerWidth, h = innerHeight;
            if (w <= 0 || h <= 0) { setTimeout(ready, 30); return; }
            if (w === previousW && h === previousH) { call(); return; }
            previousW = w; previousH = h;
            if ($backgroundMeasurement) setTimeout(ready, 16);
            else requestAnimationFrame(ready);
          }
          ready();
        }
        function positionLayout() {
          var r = getComputedStyle(document.documentElement);
          var c = getComputedStyle(container);
          var signature = [$isHorizontal ? 4 : 3, $isHorizontal, innerWidth, innerHeight, devicePixelRatio,
            r.getPropertyValue('--font').trim(), r.getPropertyValue('--fs').trim(),
            r.getPropertyValue('--lh').trim(), r.getPropertyValue('--mx').trim(),
            r.getPropertyValue('--align').trim(), c.paddingTop, c.paddingBottom];
          // Preserve existing Android/single-page layout IDs when adding desktop spreads.
          if (c.columnCount === '2') signature.push('spread');
          return JSON.stringify(signature);
        }
        function reportExactPosition(character, page) {
          var n = $isHorizontal ? currentPage : page;
          var y = $isHorizontal ? 0 : window.scrollY;
          var layout = positionLayout();
          var key = [n, character, y, layout].join('|');
          if (key === lastExactPosition) return;
          lastExactPosition = key;
          Shelest.onPositionChange(positionSegment, n, character, layout, y, cloudTextAnchor(character));
        }
        function cloudTextAnchor(character) {
          if (character < 0) return '';
          return (container.textContent || '').slice(character, character + 400).replace(/\s/g, '').slice(0, 160);
        }
        window.captureReadingPosition = function() {
          if ($isHorizontal ? !paged : !placed) return null;
          if ($isHorizontal) settle(currentPage);
          else updateScrollPage();
          var character = reportPlace();
          return {segment: positionSegment, page: $isHorizontal ? currentPage : lastReportedPage,
            character: character, layout: positionLayout(), scrollY: $isHorizontal ? 0 : window.scrollY, anchor: cloudTextAnchor(character)};
        };
    """.trimIndent()
    val fullScriptJs = positionJs + "\n" + scriptJs + "\n" + charIndexJs + "\n" + annotationJs

    return """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>
$fontFaceCss
/* Everything the appearance sheet can touch lives here as a custom property. Changing
   one is a style recalculation the page can absorb in a frame; rebuilding the document
   to change the same value is the second-long freeze this replaces. */
:root {
  --font: $fontFamilyCss;
  --fs: ${fs}px;
  --lh: $lh;
  --mx: ${mx}px;
  --bg: $bg;
  --fg: $fg;
  --accent: $accent;
  --sel: $selColor;
  --align: $justify;
}
$layoutCss
#container {
  font-family:var(--font);
  font-size:var(--fs); line-height:var(--lh);
  color:var(--fg); background:var(--bg);
  text-align:var(--align);
  word-wrap:break-word; overflow-wrap:break-word;
  -webkit-hyphens:auto; hyphens:auto;
}
p { margin:0 0 0.85em; text-indent:1.5em; }
p:first-child { text-indent:0; }
h1,h2,h3,h4 { color:var(--accent); text-indent:0; margin:1.3em 0 0.6em; line-height:1.3;
  -webkit-column-break-before:avoid; break-before:avoid; }
h1 { font-size:1.7em; } h2 { font-size:1.35em; } h3 { font-size:1.15em; }
blockquote,.epigraph,.cite {
  border-left:3px solid var(--accent); padding:6px 0 6px 14px;
  margin:1em 0; font-style:italic; opacity:0.85; }
.poem { background:rgba(128,128,128,0.08); border-radius:8px; padding:12px 16px; margin:1em 0; }
.verse-line { margin-bottom:3px; text-indent:0; }
p,li { -webkit-column-break-inside:auto !important; break-inside:auto !important; }
::selection { background:var(--sel); }
/* Search hit: stays tinted so it can be found again after the flash draws the eye. */
.shelest-hit { background:var(--sel); border-radius:3px; animation:shelestHit 0.7s ease-in-out 3; }
@keyframes shelestHit { 50% { background:var(--accent); color:var(--bg); } }
/* The line the voice is on. Quieter than a search hit on purpose: it moves every few
   seconds, and anything that flashes at that rate is a strobe rather than a pointer. The
   transition is what makes it read as one mark travelling down the page. */
.shelest-spoken, .shelest-spoken-block {
  background:var(--sel); border-radius:3px; box-shadow:0 0 0 2px var(--sel);
  transition:background 0.25s ease; }
</style>
</head>
<body>
<div id="page-frame"><div id="mask"><div id="container">$content</div></div></div>
<script>
$bridgeJs
$fullScriptJs
</script>
</body>
</html>""".trimIndent()
}

private fun needsImagesBeforePaging(content: String): Boolean {
    var at = content.indexOf("<img", ignoreCase = true)
    while (at >= 0) {
        val end = content.indexOf('>', at)
        // A tag that never closes is a document that cannot be reasoned about; wait, as before.
        if (end < 0) return true
        val tag = content.substring(at, end)
        if (!tag.contains(" width=", ignoreCase = true) || !tag.contains(" height=", ignoreCase = true)) {
            return true
        }
        at = content.indexOf("<img", end, ignoreCase = true)
    }
    return false
}


// JSON is data even when a display name or anchor contains an HTML closing tag.
private fun scriptJson(value: String): String = value.replace("<", "\\u003c")
    .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
