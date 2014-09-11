package com.ripple.core.types.shamap;


import com.ripple.core.coretypes.hash.Hash256;
import com.ripple.core.fields.Field;
import com.ripple.core.types.known.sle.LedgerEntry;
import com.ripple.core.types.known.sle.ThreadedLedgerEntry;
import com.ripple.core.types.known.sle.entries.AccountRoot;
import com.ripple.core.types.known.sle.entries.DirectoryNode;
import com.ripple.core.types.known.sle.entries.Offer;
import com.ripple.core.types.known.sle.entries.RippleState;
import com.ripple.core.types.known.tx.result.AffectedNode;
import com.ripple.core.types.known.tx.result.TransactionResult;

import java.util.*;

public class AccountStateBuilder {
    private final ShaMap state;
    private long currentLedgerIndex;
    private long currentTransactionIndex = 0;
    private String currentAccountHash;

    private TreeSet<Hash256> directoriesWithIndexesOutOfOrder = new TreeSet<Hash256>();
    private TreeSet<Hash256> directoriesModifiedMoreThanOncePerTransaction = new TreeSet<Hash256>();

    public AccountStateBuilder(ShaMap state, long currentLedgerIndex) {
        this.state = state;
        this.currentLedgerIndex = currentLedgerIndex;
    }

    public void onLedgerClose(long ledgerIndex, String accountHash, String parentHash) {
        state.updateSkipLists(ledgerIndex, Hash256.fromHex(parentHash));
        currentLedgerIndex = ledgerIndex;
        currentAccountHash = accountHash;
        currentTransactionIndex = 0;
    }

    public void onTransaction(TransactionResult tr) {
        if (tr.meta.transactionIndex().longValue() != currentTransactionIndex) throw new AssertionError();
        currentTransactionIndex++;
        directoriesModifiedMoreThanOncePerTransaction = new TreeSet<Hash256>();

        for (AffectedNode an : sortedAffectedNodes(tr)) {
            Hash256 id = an.ledgerIndex();
            LedgerEntry le = (LedgerEntry) an.nodeAsFinal();
            if (an.isCreatedNode()) {
                le.setLedgerEntryDefaults();
                state.addLE(le);

                if (le instanceof Offer) {
                    Offer offer = (Offer) le;
                    offer.setOfferDefaults();

                    for (Hash256 directory : offer.directoryIndexes()) {
                        DirectoryNode dn = getDirectoryForUpdating(directory);
                        Hash256 index = offer.index();
                        addToDirectoryNode(dn, index);
                    }
                } else if (le instanceof RippleState) {
                    RippleState state = (RippleState) le;
                    state.setRippleStateDefaults();

                    for (Hash256 directory : state.directoryIndexes()) {
                        DirectoryNode dn = getDirectoryForUpdating(directory);
                        addToDirectoryNode(dn, state.index());
                    }
                } else if (le instanceof DirectoryNode) {
                    DirectoryNode dn = (DirectoryNode) le;
                    dn.setDirectoryNodeDefaults();
                } else if (le instanceof AccountRoot) {
                    AccountRoot ar = (AccountRoot) le;
                    ar.setAccountRootDefaults();
                }

                if (le instanceof ThreadedLedgerEntry) {
                    ThreadedLedgerEntry tle = (ThreadedLedgerEntry) le;
                    tle.setThreadedLedgerEntryDefaults(tr.hash, tr.ledgerIndex);
                }
            } else if (an.isDeletedNode()) {
                directoriesWithIndexesOutOfOrder.remove(id);
                state.removeLeaf(id);

                if (le instanceof Offer) {
                    Offer offer = (Offer) le;
                    for (Hash256 directory : offer.directoryIndexes()) {
                        try {
                            DirectoryNode dn = getDirectoryForUpdating(directory);
                            if (dn != null) {
                                Hash256 index = offer.index();
                                if (dn.owner() != null) {
                                    directoryRemoveUnstable(dn, index);
                                } else {
                                    directoryRemoveStable(dn, index);
                                }
                            }
                        } catch (Exception e) {
                            //
                        }
                    }
                } else if (le instanceof RippleState) {
                    RippleState state = (RippleState) le;
                    for (Hash256 directory : state.directoryIndexes()) {
                        try {
                            DirectoryNode dn = getDirectoryForUpdating(directory);
                            if (dn != null) {
                                directoryRemoveUnstable(dn, state.index());
                            }
                        } catch (Exception e) {
                            //
                        }
                    }
                }
            } else if (an.isModifiedNode()) {
                LedgerEntryLeaf leaf = (LedgerEntryLeaf) state.getLeafForUpdating(id);
                LedgerEntry leModded = leaf.le;

                if (le instanceof ThreadedLedgerEntry) {
                    ThreadedLedgerEntry tle = (ThreadedLedgerEntry) le;
                    tle.previousTxnID(tr.hash);
                    tle.previousTxnLgrSeq(tr.ledgerIndex);
                }
                for (Field field : le) {
                    if (field == Field.LedgerIndex) {
                        continue;
                    }
                    leModded.put(field, le.get(field));
                }
            }
        }
    }

    public static <E> Collection<E> makeCollection(Iterable<E> iter) {
        Collection<E> list = new ArrayList<E>();
        for (E item : iter) {
            list.add(item);
        }
        return list;
    }
    private ArrayList<AffectedNode> sortedAffectedNodes(TransactionResult tr) {
        ArrayList<AffectedNode> sorted = new ArrayList<AffectedNode>(makeCollection(tr.meta.affectedNodes()));
        Collections.sort(sorted, new Comparator<AffectedNode>() {
            @Override
            public int compare(AffectedNode o1, AffectedNode o2) {
                return ord(o1) - ord(o2);
            }

            private int ord(AffectedNode o1) {
                switch (o1.ledgerEntryType()) {
                    case DirectoryNode:
                        return 1;
                    case RippleState:
                        return 2;
                    case Offer:
                        return 3;
                    default:
                        return 4;
                }
            }
        });
        return sorted;
    }

    private void onDirectoryModified(DirectoryNode dn) {
        Hash256 index = dn.index();
        if (directoriesModifiedMoreThanOncePerTransaction.contains(index)) {
            directoriesWithIndexesOutOfOrder.add(index);
        }
        else {
            directoriesModifiedMoreThanOncePerTransaction.add(index);
        }
    }
    private void directoryRemoveStable(DirectoryNode dn, Hash256 index) {
        onDirectoryModified(dn);
        dn.indexes().remove(index);
    }
    private void directoryRemoveUnstable(DirectoryNode dn, Hash256 index) {
        onDirectoryModified(dn);
        dn.indexes().removeUnstable(index);
    }
    private void addToDirectoryNode(DirectoryNode dn, Hash256 index) {
        onDirectoryModified(dn);
        dn.indexes().add(index);
    }
    private DirectoryNode getDirectoryForUpdating(Hash256 directoryIndex) {
        LedgerEntryLeaf leaf = (LedgerEntryLeaf) state.getLeafForUpdating(directoryIndex);
        if (leaf == null) {
            return null;
        }
        return (DirectoryNode) leaf.le;
    }

    public ShaMap state() {
        return state;
    }
    public long currentLedgerIndex() {
        return currentLedgerIndex;
    }
    public String currentAccountHash() {
        return currentAccountHash;
    }
    // TODO
    public Hash256 accountHash() {
        return Hash256.fromHex(currentAccountHash);
    }
    public TreeSet<Hash256> directoriesWithIndexesOutOfOrder() {
        return directoriesWithIndexesOutOfOrder;
    }

    public boolean bad() {
        return !state.hash().toHex().equals(currentAccountHash);
    }
}