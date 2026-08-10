/*
  The application shell's two behaviours. Nothing here knows about a screen.

  Both are progressive: without this file the omnibox is still a search field that submits, and
  "+ Dodaj" is a button that does nothing visible. Neither is the only way to reach anything it
  opens — /properties/new, /dodaj/lokal and /dodaj/najem are all ordinary URLs.
*/
(function () {
    "use strict";

    /*
      ⌘K (macOS) and Ctrl+K elsewhere focus the omnibox, which is what its own badge promises. The
      badge is rendered unconditionally rather than sniffed per platform: Ctrl+K does the same thing
      here, and a label that changes shape depending on the machine is a label nobody can be told
      about over the phone.

      preventDefault matters — Ctrl+K is "insert link" in some editors and Firefox's own web-search
      focus, and leaving both to fire means the shortcut sometimes works and sometimes fights.
      select() rather than plain focus so a second press replaces the previous query instead of
      appending to it, which is the behaviour of every command palette this imitates.
    */
    document.addEventListener("keydown", function (event) {
        if (event.key !== "k" && event.key !== "K") {
            return;
        }
        if (!event.metaKey && !event.ctrlKey) {
            return;
        }
        var search = document.querySelector(".headerbar__search");
        if (!search) {
            return;
        }
        event.preventDefault();
        search.focus();
        search.select();
    });

    /*
      Any element naming a dialog by id opens it modally. Written as a delegated listener on a data
      attribute rather than a direct binding, so a second opener costs a markup attribute and no
      JavaScript.

      showModal(), not the `open` attribute: only the modal form gives the backdrop, Esc, focus
      containment and inertness of the page behind. A dialog opened by setting `open` looks
      identical and has none of them.
    */
    document.addEventListener("click", function (event) {
        var opener = event.target.closest("[data-opens-dialog]");
        if (!opener) {
            return;
        }
        var dialog = document.getElementById(opener.getAttribute("data-opens-dialog"));
        if (!dialog || typeof dialog.showModal !== "function") {
            return;
        }
        event.preventDefault();
        dialog.showModal();
    });

    /*
      Clicking the backdrop closes. The dialog element's own box is what receives the click when the
      backdrop is hit — the event target is the <dialog> itself rather than any child — so comparing
      the target to the dialog distinguishes "outside" from "on a row" without measuring anything.
    */
    document.addEventListener("click", function (event) {
        if (event.target instanceof HTMLDialogElement && event.target.open) {
            event.target.close();
        }
    });
}());
