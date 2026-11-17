create extension if not exists vector;

alter table app_rag_document
    add column if not exists embedding vector(384);

create index if not exists app_rag_document_embedding_idx
    on app_rag_document using ivfflat (embedding vector_l2_ops);
