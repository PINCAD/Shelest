import type { Book } from "./types";
import { sharedCore } from "./sharedCore";

/** Importing a file is not a reading event and must never replace another device's place. */
export function progressTime(book: Pick<Book, "progressUpdatedAt" | "position" | "page" | "lastOpenedAt">): number {
  return sharedCore.progressTime(book.progressUpdatedAt ?? -1, book.lastOpenedAt, book.position > 0 || book.page > 1);
}
